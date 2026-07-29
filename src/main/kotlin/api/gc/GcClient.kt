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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.concurrent.thread

/** Supplies a player's recent match ids (newest first) — the seam a [bot.GcMatchSource] polls. */
interface MatchHistoryProvider {
    suspend fun recentMatchIds(accountId: String): List<Long>
}

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
        const val WELCOME_TIMEOUT_MS = 15_000L
        const val REQUEST_TIMEOUT_MS = 20_000L
        const val LOGIN_ID = 149
    }

    private val steamClient = SteamClient()
    private val manager = CallbackManager(steamClient)
    private val steamUser = requireNotNull(steamClient.getHandler(SteamUser::class.java))
    private val gameCoordinator = requireNotNull(steamClient.getHandler(SteamGameCoordinator::class.java))

    @Volatile private var running = false
    @Volatile private var gcWelcomed = false
    @Volatile private var helloCount = 0
    // Whether the last login attempt used a stored refresh token, and whether to
    // skip the token and force a fresh credential login (set after a token is rejected).
    @Volatile private var usedStoredToken = false
    @Volatile private var forceCredentialAuth = false
    private val welcome = CompletableDeferred<Unit>()

    // The GC response isn't tagged with the requested account_id, so only one
    // request is in flight at a time and its response completes [pending].
    private val requestMutex = Mutex()
    @Volatile private var pending: CompletableDeferred<List<Long>>? = null

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

    override suspend fun recentMatchIds(accountId: String): List<Long> = requestMutex.withLock {
        // First make sure the GC session is up. Separated from the request wait so
        // the logs distinguish "no GC session" from "GC didn't answer the request".
        if (!gcWelcomed) {
            try {
                withTimeout(WELCOME_TIMEOUT_MS) { welcome.await() }
            } catch (e: TimeoutCancellationException) {
                println("GC bot: no ClientWelcome after ${WELCOME_TIMEOUT_MS}ms — GC session not established; skipping $accountId this cycle.")
                return@withLock emptyList()
            }
        }

        val steam3 = accountId.toLong().toInt()
        val deferred = CompletableDeferred<List<Long>>()
        pending = deferred
        return@withLock try {
            println("GC bot: -> GetMatchHistory account=$accountId (steam3=$steam3).")
            sendGetMatchHistory(steam3)
            withTimeout(REQUEST_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            println("GC bot: no GetMatchHistory response for $accountId within ${REQUEST_TIMEOUT_MS}ms.")
            emptyList()
        } finally {
            pending = null
        }
    }

    private fun onConnected() {
        // Skip the stored token if it was just rejected, so we re-auth with credentials.
        val storedToken = if (forceCredentialAuth) null
            else initialRefreshToken ?: tokenFile.takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null }
        usedStoredToken = storedToken != null
        try {
            if (storedToken != null) {
                println("GC bot: logging in with stored refresh token.")
                logOnWithToken(username, storedToken)
            } else {
                requireNotNull(password) { "No usable refresh token and no STEAM_PASSWORD provided." }
                println("GC bot: authenticating with credentials (Steam Guard/MFA prompt expected)...")
                val authDetails = AuthSessionDetails().apply {
                    username = this@GcClient.username
                    password = this@GcClient.password
                    // Long-lived refresh token that can be reused across restarts;
                    // false issues a short-lived token that fails on the next run.
                    persistentSession = true
                    authenticator = UserConsoleAuthenticator()
                }
                val authSession = steamClient.authentication.beginAuthSessionViaCredentials(authDetails).get()
                val poll = authSession.pollingWaitForResult().get()
                runCatching { tokenFile.writeText(poll.refreshToken) }.onSuccess {
                    println("GC bot: saved refresh token to ${tokenFile.absolutePath}; set STEAM_REFRESH_TOKEN to it for headless runs.")
                }
                logOnWithToken(poll.accountName, poll.refreshToken)
            }
        } catch (e: Exception) {
            println("GC bot: login failed: ${e.message}")
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
        gcWelcomed = false
        if (running && !callback.isUserInitiated) {
            runCatching { Thread.sleep(3_000L); steamClient.connect() }
        }
    }

    private fun onLoggedOn(callback: LoggedOnCallback) {
        if (callback.result != EResult.OK) {
            if (usedStoredToken) {
                // Stale token or token for a different account. Discard it and let the
                // automatic reconnect re-authenticate with username/password (+ MFA).
                println("GC bot: stored refresh token rejected (${callback.result}); discarding it and re-authenticating with credentials.")
                runCatching { tokenFile.delete() }
                forceCredentialAuth = true
            } else {
                println("GC bot: unable to log on with credentials: ${callback.result} / ${callback.extendedResult}. Check STEAM_USERNAME / STEAM_PASSWORD.")
            }
            return
        }
        // Good login: allow the stored token to be reused on future reconnects.
        forceCredentialAuth = false
        println("GC bot: logged on; sending games-played($DEADLOCK_APP_ID) and hellos to establish the Deadlock GC session...")
        startPlayingGame(DEADLOCK_APP_ID)
        // Some GCs only welcome after repeated hellos; send until welcomed.
        thread(name = "steam-gc-hello", isDaemon = true) {
            while (running && !gcWelcomed) {
                sendHello()
                Thread.sleep(5_000L)
            }
        }
    }

    private fun onGcMessage(callback: MessageCallback) {
        // Log every inbound GC message so we can see whether the welcome (4004)
        // and the match-history response (9113) actually arrive.
        println("GC bot: <- GC message appID=${callback.appID} msgType=${callback.message.msgType}")
        if (callback.appID != DEADLOCK_APP_ID) return
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
                val ids = if (body.result == CitadelMatchHistory.CMsgClientToGCGetMatchHistoryResponse.EResult.k_eResult_Success) {
                    body.matchesList.map { it.matchId }
                } else {
                    println("GC bot: match-history response result=${body.result}")
                    emptyList()
                }
                pending?.let { if (!it.isCompleted) it.complete(ids) }
            }
        }
    }

    private fun sendGetMatchHistory(steam3AccountId: Int) {
        val request = ClientGCMsgProtobuf<CitadelMatchHistory.CMsgClientToGCGetMatchHistory.Builder>(
            CitadelMatchHistory.CMsgClientToGCGetMatchHistory::class.java,
            MSG_GET_MATCH_HISTORY,
        )
        request.body.setAccountId(steam3AccountId)
        gameCoordinator.send(request, DEADLOCK_APP_ID)
    }

    private fun sendHello() {
        helloCount++
        println("GC bot: -> ClientHello #$helloCount (waiting for GC welcome).")
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
