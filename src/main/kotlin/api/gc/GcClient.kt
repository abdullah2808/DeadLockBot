package api.gc

import com.google.protobuf.Message
import gc.citadel.CitadelMatchHistory
import `in`.dragonbra.javasteam.base.ClientMsgProtobuf
import `in`.dragonbra.javasteam.base.gc.ClientGCMsgProtobuf
import `in`.dragonbra.javasteam.enums.EMsg
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.enums.EFriendRelationship
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserver
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.UserConsoleAuthenticator
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.SteamFriends
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.FriendsListCallback
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.FriendAddedCallback
import `in`.dragonbra.javasteam.steam.handlers.steamgamecoordinator.SteamGameCoordinator
import `in`.dragonbra.javasteam.steam.handlers.steamgamecoordinator.callback.MessageCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.types.JobID
import `in`.dragonbra.javasteam.types.SteamID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import models.MatchHistoryDTO
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.concurrent.thread

interface MatchHistoryProvider {
    suspend fun recentMatchIds(accountId: String): List<Long>
    suspend fun recentHistory(accountId: String): MatchHistorySnapshot = MatchHistorySnapshot(recentMatchIds(accountId), emptyList())
    suspend fun cachedMatch(accountId: String, matchId: Long): MatchHistoryDTO? = null
    suspend fun metadataDownload(accountId: String, matchId: Long): GcMetadataDownload? = null
    fun steamBotIdentity(): SteamBotIdentity? = null
    fun steamPlayerName(accountId: String): String? = null
    fun friendship(accountId: String): SteamFriendship = SteamFriendship.UNKNOWN
    fun autoAcceptFriendsEnabled(): Boolean = false
    fun authorizeSignupFriendRequest(accountId: String) {}
    fun updateTrackedAccounts(accounts: Set<String>) {}
    fun close() {}
}

data class MatchHistorySnapshot(val ids: List<Long>, val summaries: List<MatchHistoryDTO>)
class GcHistoryPermissionException(val accountId: String) : IOException("GC denied match-history access for $accountId")

/** Authenticated Steam GC session shared by history, metadata, and incoming friend acceptance. */
class GcClient(
    private val username: String,
    private val password: String?,
    private val initialRefreshToken: String?,
    private val tokenFile: File = File("steam_refresh_token.txt"),
    private val autoAcceptFriends: Boolean = true,
) : MatchHistoryProvider {
    private companion object {
        const val DEADLOCK_APP_ID = 1422450
        const val MSG_GC_CLIENT_WELCOME = 4004
        const val MSG_GC_CLIENT_HELLO = 4006
        const val MSG_GET_HISTORY = 9112
        const val MSG_HISTORY_RESPONSE = 9113
        const val MSG_GET_METADATA = 9167
        const val MSG_METADATA_RESPONSE = 9168
        const val REQUEST_TIMEOUT_MS = 20_000L
    }
    private val steamClient = SteamClient()
    private val manager = CallbackManager(steamClient)
    private val steamUser = requireNotNull(steamClient.getHandler(SteamUser::class.java))
    private val steamFriends = requireNotNull(steamClient.getHandler(SteamFriends::class.java))
    private val gameCoordinator = requireNotNull(steamClient.getHandler(SteamGameCoordinator::class.java))
    private val router = GcResponseRouter()
    private val requestGate = GcRequestGate()
    private val friendPolicy = SteamFriendPolicy(enabled = autoAcceptFriends, accept = { account ->
        steamFriends.addFriend(SteamID(76561197960265728L + account.toLong()))
        println("Steam bot: accepting incoming friend request from $account.")
    })
    @Volatile private var running = false
    @Volatile private var connected = false
    @Volatile private var gcWelcomed = false
    @Volatile private var welcome = CompletableDeferred<Unit>()
    @Volatile private var identity: SteamBotIdentity? = null
    @Volatile private var reconnectRequested = false

    fun start() {
        running = true
        manager.subscribe(ConnectedCallback::class.java) { onConnected() }
        manager.subscribe(DisconnectedCallback::class.java) { onDisconnected(it) }
        manager.subscribe(LoggedOnCallback::class.java) { onLoggedOn(it) }
        manager.subscribe(LoggedOffCallback::class.java) { println("GC bot: logged off (${it.result}).") }
        manager.subscribe(MessageCallback::class.java) { onGcMessage(it) }
        manager.subscribe(FriendsListCallback::class.java) { callback ->
            val updates = callback.friendList.filter { it.steamID.isIndividualAccount }
                .associate { friend -> friend.steamID.accountID.toString() to when (friend.relationship) {
                    EFriendRelationship.Friend -> SteamFriendship.FRIEND
                    EFriendRelationship.RequestRecipient -> SteamFriendship.INCOMING
                    EFriendRelationship.RequestInitiator -> SteamFriendship.OUTGOING
                    EFriendRelationship.Blocked, EFriendRelationship.Ignored, EFriendRelationship.IgnoredFriend -> SteamFriendship.BLOCKED
                    else -> SteamFriendship.NONE
                } }
            friendPolicy.updateRelationships(updates, callback.isIncremental)
        }
        manager.subscribe(FriendAddedCallback::class.java) { callback ->
            friendPolicy.confirm(callback.steamID.accountID.toString(), callback.result == EResult.OK)
            println("Steam bot: friend acceptance for ${callback.steamID.accountID}: ${callback.result}.")
        }
        thread(name = "steam-gc-callbacks", isDaemon = true) {
            steamClient.connect()
            while (running) manager.runWaitCallbacks(1000L)
        }
    }
    override fun close() {
        running = false
        invalidateSession()
        runCatching { steamUser.logOff(); steamClient.disconnect() }
    }
    fun stop() = close()
    override fun steamBotIdentity() = identity
    override fun steamPlayerName(accountId: String): String? {
        if (friendPolicy.status(accountId) != SteamFriendship.FRIEND) return null
        return steamFriends.getFriendPersonaName(SteamID(76561197960265728L + accountId.toLong()))
    }
    override fun friendship(accountId: String) = friendPolicy.status(accountId)
    override fun autoAcceptFriendsEnabled() = autoAcceptFriends
    override fun authorizeSignupFriendRequest(accountId: String) = friendPolicy.authorizeSignup(accountId)
    override fun updateTrackedAccounts(accounts: Set<String>) = friendPolicy.updateTracked(accounts)
    override suspend fun recentMatchIds(accountId: String): List<Long> = recentHistory(accountId).ids

    override suspend fun recentHistory(accountId: String): MatchHistorySnapshot {
        val body = request(MSG_HISTORY_RESPONSE) { job ->
            val message = ClientGCMsgProtobuf<CitadelMatchHistory.CMsgClientToGCGetMatchHistory.Builder>(
                CitadelMatchHistory.CMsgClientToGCGetMatchHistory::class.java, MSG_GET_HISTORY)
            message.sourceJobID = job
            message.body.accountId = accountId.toLong().toInt()
            gameCoordinator.send(message, DEADLOCK_APP_ID)
        } as CitadelMatchHistory.CMsgClientToGCGetMatchHistoryResponse
        if (body.result == CitadelMatchHistory.CMsgClientToGCGetMatchHistoryResponse.EResult.k_eResult_InvalidPermission)
            throw GcHistoryPermissionException(accountId)
        if (body.result != CitadelMatchHistory.CMsgClientToGCGetMatchHistoryResponse.EResult.k_eResult_Success)
            throw IOException("GC history unavailable: ${body.result}")
        val summaries = body.matchesList.mapNotNull { it.toSummary(accountId) }
        println("GC history for $accountId: ${body.matchesCount} matches, ${summaries.size} complete summaries.")
        return MatchHistorySnapshot(body.matchesList.map { it.matchId }, summaries)
    }

    override suspend fun metadataDownload(accountId: String, matchId: Long): GcMetadataDownload? {
        val body = request(MSG_METADATA_RESPONSE) { job ->
            val message = ClientGCMsgProtobuf<CitadelMatchHistory.CMsgClientToGCGetMatchMetaData.Builder>(
                CitadelMatchHistory.CMsgClientToGCGetMatchMetaData::class.java, MSG_GET_METADATA)
            message.sourceJobID = job
            message.body.matchId = matchId
            message.body.targetAccountId = accountId.toLong().toInt()
            gameCoordinator.send(message, DEADLOCK_APP_ID)
        } as CitadelMatchHistory.CMsgClientToGCGetMatchMetaDataResponse
        if (body.result != CitadelMatchHistory.CMsgClientToGCGetMatchMetaDataResponse.EResult.k_eResult_Success)
            throw IOException("GC metadata unavailable for $matchId: ${body.result}")
        if (!body.hasMetadataSalt() || body.metadataSalt == 0 || !body.hasReplayGroupId() || body.replayGroupId == 0)
            throw IOException("GC metadata download references are not ready for $matchId")
        return GcMetadataDownload(matchId, body.replayGroupId.toUInt().toLong(), body.metadataSalt.toUInt().toLong())
    }

    private suspend fun request(responseType: Int, send: (JobID) -> Unit): Message = requestGate.request<Message>(
        responseType,
        result = { response -> when (response) {
            is CitadelMatchHistory.CMsgClientToGCGetMatchHistoryResponse -> response.result.number
            is CitadelMatchHistory.CMsgClientToGCGetMatchMetaDataResponse -> response.result.number
            else -> error("Unexpected GC response: ${response.javaClass.simpleName}")
        } },
    ) {
        var answer: CompletableDeferred<Message>? = null
        try {
            withTimeout(REQUEST_TIMEOUT_MS) { welcome.await() }
            val job = steamClient.getNextJobID()
            val waiting = router.expect(responseType, job.value)
            answer = waiting
            send(job)
            withTimeout(REQUEST_TIMEOUT_MS) { waiting.await() }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            resetConnection()
            throw IOException("GC request timed out; reconnecting before another query", e)
        } catch (e: CancellationException) {
            if (answer != null) resetConnection()
            throw e
        } finally {
            answer?.let(router::clear)
        }
    }

    private fun onConnected() {
        connected = true
        try {
            val stored = initialRefreshToken ?: tokenFile.takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null }
            if (stored != null) {
                println("GC bot: logging in with stored refresh token.")
                logOnWithToken(username, stored)
            } else {
                requireNotNull(password) { "No refresh token and no STEAM_PASSWORD provided." }
                println("GC bot: authenticating with credentials (Steam Guard prompt expected on first run)...")
                val details = AuthSessionDetails().apply {
                    username = this@GcClient.username
                    password = this@GcClient.password
                    persistentSession = true
                    authenticator = UserConsoleAuthenticator()
                }
                val poll = steamClient.authentication.beginAuthSessionViaCredentials(details).get().pollingWaitForResult().get()
                saveRefreshToken(tokenFile, poll.refreshToken)
                println("GC bot: saved refresh token; ready for headless runs.")
                logOnWithToken(poll.accountName, poll.refreshToken)
            }
        } catch (e: Exception) {
            println("GC bot: login failed (${e.javaClass.simpleName}).")
            runCatching { steamUser.logOff() }
        }
    }
    private fun logOnWithToken(accountName: String, token: String) {
        steamUser.logOn(LogOnDetails().apply { username = accountName; accessToken = token; loginID = 149 })
    }
    private fun onDisconnected(callback: DisconnectedCallback) {
        invalidateSession()
        val reconnect = reconnectRequested || !callback.isUserInitiated
        reconnectRequested = false
        if (running && reconnect) runCatching {
            Thread.sleep(3_000L)
            if (running) steamClient.connect()
        }
    }
    private fun resetConnection() {
        reconnectRequested = running
        invalidateSession()
        steamClient.disconnect()
    }
    private fun invalidateSession() {
        connected = false
        friendPolicy.disconnected()
        if (gcWelcomed || welcome.isCompleted) welcome = CompletableDeferred()
        gcWelcomed = false
        router.fail(IOException("Steam GC disconnected"))
    }
    private fun onLoggedOn(callback: LoggedOnCallback) {
        if (callback.result != EResult.OK) {
            println("GC bot: unable to log on: ${callback.result} / ${callback.extendedResult}")
            return
        }
        identity = steamClient.steamID?.convertToUInt64()?.let(::SteamBotIdentity)
        println("GC bot: logged on; establishing Deadlock GC session...")
        val played = ClientMsgProtobuf<SteammessagesClientserver.CMsgClientGamesPlayed.Builder>(
            SteammessagesClientserver.CMsgClientGamesPlayed::class.java, EMsg.ClientGamesPlayed)
        played.body.addGamesPlayedBuilder().gameId = DEADLOCK_APP_ID.toLong()
        steamClient.send(played)
        val readiness = welcome
        thread(name = "steam-gc-hello", isDaemon = true) {
            while (running && connected && !gcWelcomed && welcome === readiness) {
                val hello = ClientGCMsgProtobuf<CitadelMatchHistory.CMsgClientHello.Builder>(
                    CitadelMatchHistory.CMsgClientHello::class.java, MSG_GC_CLIENT_HELLO)
                gameCoordinator.send(hello, DEADLOCK_APP_ID)
                Thread.sleep(5_000L)
            }
        }
    }
    private fun onGcMessage(callback: MessageCallback) {
        if (callback.appID != DEADLOCK_APP_ID || !connected) return
        try {
            when (callback.message.msgType) {
                MSG_GC_CLIENT_WELCOME -> {
                    gcWelcomed = true
                    if (!welcome.isCompleted) {
                        welcome.complete(Unit)
                        println("GC bot: Deadlock GC welcomed us; ready to query match history.")
                    }
                }
                MSG_HISTORY_RESPONSE -> {
                    val response = ClientGCMsgProtobuf<CitadelMatchHistory.CMsgClientToGCGetMatchHistoryResponse.Builder>(
                        CitadelMatchHistory.CMsgClientToGCGetMatchHistoryResponse::class.java, callback.message)
                    router.complete(MSG_HISTORY_RESPONSE, response.targetJobID.value, response.body.build())
                }
                MSG_METADATA_RESPONSE -> {
                    val response = ClientGCMsgProtobuf<CitadelMatchHistory.CMsgClientToGCGetMatchMetaDataResponse.Builder>(
                        CitadelMatchHistory.CMsgClientToGCGetMatchMetaDataResponse::class.java, callback.message)
                    router.complete(MSG_METADATA_RESPONSE, response.targetJobID.value, response.body.build())
                }
            }
        } catch (e: Exception) { router.fail(IOException("Could not decode GC response", e)) }
    }
}

/** Write privately, then replace the old token only after a complete write. */
internal fun saveRefreshToken(file: File, token: String) {
    val target = file.toPath().toAbsolutePath()
    Files.createDirectories(target.parent)
    val temp = Files.createTempFile(target.parent, ".steam-token-", ".tmp",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    try {
        Files.writeString(temp, token)
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { Files.deleteIfExists(temp) }
}
