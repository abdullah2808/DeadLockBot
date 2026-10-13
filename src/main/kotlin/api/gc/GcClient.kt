package api.gc

import gc.citadel.CitadelMatchHistory
import `in`.dragonbra.javasteam.base.ClientMsgProtobuf
import `in`.dragonbra.javasteam.base.gc.ClientGCMsgProtobuf
import `in`.dragonbra.javasteam.enums.EMsg
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserver
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.UserConsoleAuthenticator
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import models.MatchHistoryDTO
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.concurrent.thread

/** Supplies a player's recent match ids (newest first) — the seam a [bot.GcMatchSource] polls. */
interface MatchHistoryProvider {
    suspend fun recentMatchIds(accountId: String): List<Long>
    suspend fun recentHistory(accountId: String): MatchHistorySnapshot =
        MatchHistorySnapshot(recentMatchIds(accountId), emptyList())
    suspend fun cachedMatch(accountId: String, matchId: Long): MatchHistoryDTO? = null
}

data class MatchHistorySnapshot(val ids: List<Long>, val summaries: List<MatchHistoryDTO>)

/**
 * Talks to the Deadlock Game Coordinator via a logged-in Steam bot account
 * (JavaSteam). This is the authoritative source for a specific player's match
 * history — unlike deadlock-api's per-player index it does not lag, and unlike
 * the active feed it is not limited to the top spectated matches.
 *
 * Flow (see JavaSteam's CS2 GC sample): connect → authenticate (stored refresh
 * token, else interactive credentials) → tell Steam we're playing Deadlock →
 * GC ClientHello until ClientWelcome → then `CMsgClientToGCGetMatchHistory`
 * (msg 9112) per account, correlating the `...Response` (9113).
 *
 * NOTE: The live Steam/GC round-trip cannot be exercised without a real bot
 * account, so this class is verified by compilation and the offline
 * [bot.GcMatchSource] tests; the on-wire behaviour needs a first run with
 * credentials. The first credential login triggers a Steam Guard prompt and
 * persists a refresh token for subsequent headless runs.
 */
class GcClient(
    private val username: String,
    private val password: String?,
    private val initialRefreshToken: String?,
    private val tokenFile: File = File("steam_refresh_token.txt"),
) : MatchHistoryProvider {

    private companion object {
        const val DEADLOCK_APP_ID = 1422450
        const val MSG_GET_MATCH_HISTORY = 9112
        const val MSG_GET_MATCH_HISTORY_RESPONSE = 9113
        // EGCBaseClientMsg (game-agnostic GC-SDK handshake).
        const val MSG_GC_CLIENT_HELLO = 4006
        const val MSG_GC_CLIENT_WELCOME = 4004
        const val REQUEST_TIMEOUT_MS = 20_000L
        const val LOGIN_ID = 149
    }

    private val steamClient = SteamClient()
    private val manager = CallbackManager(steamClient)
    private val steamUser = requireNotNull(steamClient.getHandler(SteamUser::class.java))
    private val gameCoordinator = requireNotNull(steamClient.getHandler(SteamGameCoordinator::class.java))

    @Volatile private var running = false
    @Volatile private var gcWelcomed = false
    @Volatile private var welcome = CompletableDeferred<Unit>()
    @Volatile private var connected = false

    // The GC response isn't tagged with the requested account_id, so only one
    // request is in flight at a time and its response completes [pending].
    private val requestMutex = Mutex()
    @Volatile private var pending: CompletableDeferred<MatchHistorySnapshot>? = null
    @Volatile private var pendingAccount: String? = null

    /** Connects and runs the Steam callback loop on a daemon thread. Returns immediately. */
    fun start() {
        running = true
        manager.subscribe(ConnectedCallback::class.java) { onConnected() }
        manager.subscribe(DisconnectedCallback::class.java) { onDisconnected(it) }
        manager.subscribe(LoggedOnCallback::class.java) { onLoggedOn(it) }
        manager.subscribe(LoggedOffCallback::class.java) { cb: LoggedOffCallback -> println("GC bot: logged off (${cb.result}).") }
        manager.subscribe(MessageCallback::class.java) { onGcMessage(it) }

        thread(name = "steam-gc-callbacks", isDaemon = true) {
            steamClient.connect()
            while (running) manager.runWaitCallbacks(1000L)
        }
    }

    fun stop() {
        running = false
        runCatching { steamUser.logOff() }
    }

    override suspend fun recentMatchIds(accountId: String): List<Long> = recentHistory(accountId).ids

    override suspend fun recentHistory(accountId: String): MatchHistorySnapshot = requestMutex.withLock {
        val deferred = CompletableDeferred<MatchHistorySnapshot>()
        try {
            // A reconnect must receive a fresh welcome before querying again.
            withTimeout(REQUEST_TIMEOUT_MS) { welcome.await() }
            pending = deferred
            pendingAccount = accountId
            sendGetMatchHistory(accountId)
            withTimeout(REQUEST_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            invalidateSession()
            steamClient.disconnect()
            throw IOException("GC history timed out; reconnecting before another query", e)
        } finally {
            pending = null
            pendingAccount = null
        }
    }

    private fun onConnected() {
        connected = true
        try {
            val storedToken = initialRefreshToken
                ?: tokenFile.takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null }
            if (storedToken != null) {
                println("GC bot: logging in with stored refresh token.")
                logOnWithToken(username, storedToken)
            } else {
                requireNotNull(password) { "No refresh token and no STEAM_PASSWORD provided." }
                println("GC bot: authenticating with credentials (Steam Guard prompt expected on first run)...")
                val authDetails = AuthSessionDetails().apply {
                    username = this@GcClient.username
                    password = this@GcClient.password
                    persistentSession = true
                    authenticator = UserConsoleAuthenticator()
                }
                val authSession = steamClient.authentication.beginAuthSessionViaCredentials(authDetails).get()
                val poll = authSession.pollingWaitForResult().get()
                saveRefreshToken(tokenFile, poll.refreshToken)
                println("GC bot: saved refresh token; ready for headless runs.")
                logOnWithToken(poll.accountName, poll.refreshToken)
            }
        } catch (e: Exception) {
            println("GC bot: login failed (${e.javaClass.simpleName}).")
            runCatching { steamUser.logOff() }
        }
    }

    private fun logOnWithToken(accountName: String, refreshToken: String) {
        val details = LogOnDetails()
        details.username = accountName
        details.accessToken = refreshToken
        details.loginID = LOGIN_ID
        steamUser.logOn(details)
    }

    private fun onDisconnected(callback: DisconnectedCallback) {
        invalidateSession()
        if (running && !callback.isUserInitiated) {
            runCatching { Thread.sleep(3_000L); steamClient.connect() }
        }
    }

    private fun invalidateSession() {
        connected = false
        if (gcWelcomed || welcome.isCompleted) welcome = CompletableDeferred()
        gcWelcomed = false
        pending?.let { if (!it.isCompleted) it.completeExceptionally(IOException("Steam GC disconnected")) }
        pending = null
        pendingAccount = null
    }

    private fun onLoggedOn(callback: LoggedOnCallback) {
        if (callback.result != EResult.OK) {
            println("GC bot: unable to log on: ${callback.result} / ${callback.extendedResult}")
            return
        }
        println("GC bot: logged on; establishing Deadlock GC session...")
        startPlayingGame(DEADLOCK_APP_ID)
        // Some GCs only welcome after repeated hellos; send until welcomed.
        val readiness = welcome
        thread(name = "steam-gc-hello", isDaemon = true) {
            while (running && connected && !gcWelcomed && welcome === readiness) {
                sendHello()
                Thread.sleep(5_000L)
            }
        }
    }

    private fun onGcMessage(callback: MessageCallback) {
        if (callback.appID != DEADLOCK_APP_ID || !connected) return
        when (callback.message.msgType) {
            MSG_GC_CLIENT_WELCOME -> {
                if (!gcWelcomed) {
                    gcWelcomed = true
                    if (!welcome.isCompleted) welcome.complete(Unit)
                    println("GC bot: Deadlock GC welcomed us; ready to query match history.")
                }
            }
            MSG_GET_MATCH_HISTORY_RESPONSE -> {
                val response = ClientGCMsgProtobuf<CitadelMatchHistory.CMsgClientToGCGetMatchHistoryResponse.Builder>(
                    CitadelMatchHistory.CMsgClientToGCGetMatchHistoryResponse::class.java,
                    callback.message,
                )
                val body = response.body
                val target = pending ?: return
                val account = pendingAccount ?: return
                if (body.result == CitadelMatchHistory.CMsgClientToGCGetMatchHistoryResponse.EResult.k_eResult_Success) {
                    val summaries = body.matchesList.mapNotNull { it.toSummary(account) }
                    println("GC history for $account: ${body.matchesCount} matches, ${summaries.size} complete summaries.")
                    if (!target.isCompleted) target.complete(MatchHistorySnapshot(body.matchesList.map { it.matchId }, summaries))
                } else {
                    println("GC bot: match-history response result=${body.result}")
                    if (!target.isCompleted) target.completeExceptionally(IllegalStateException("GC history unavailable: ${body.result}"))
                }
            }
        }
    }

    private fun sendGetMatchHistory(accountId: String) {
        val request = ClientGCMsgProtobuf<CitadelMatchHistory.CMsgClientToGCGetMatchHistory.Builder>(
            CitadelMatchHistory.CMsgClientToGCGetMatchHistory::class.java,
            MSG_GET_MATCH_HISTORY,
        )
        // account_id is uint32; toInt() carries the correct 32-bit value onto the wire.
        request.body.setAccountId(accountId.toLong().toInt())
        gameCoordinator.send(request, DEADLOCK_APP_ID)
    }

    private fun sendHello() {
        val hello = ClientGCMsgProtobuf<CitadelMatchHistory.CMsgClientHello.Builder>(
            CitadelMatchHistory.CMsgClientHello::class.java,
            MSG_GC_CLIENT_HELLO,
        )
        gameCoordinator.send(hello, DEADLOCK_APP_ID)
    }

    private fun startPlayingGame(appId: Int) {
        val gamesPlayed = ClientMsgProtobuf<SteammessagesClientserver.CMsgClientGamesPlayed.Builder>(
            SteammessagesClientserver.CMsgClientGamesPlayed::class.java,
            EMsg.ClientGamesPlayed,
        )
        gamesPlayed.body.addGamesPlayedBuilder().setGameId(appId.toLong())
        steamClient.send(gamesPlayed)
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
    } finally {
        Files.deleteIfExists(temp)
    }
}
