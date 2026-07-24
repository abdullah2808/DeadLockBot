import api.gc.GcClient
import api.gc.MatchHistoryProvider
import bot.handleCommands
import bot.registerCommands
import bot.startScheduler
import data.DatabaseFactory
import dev.kord.core.Kord
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/*
 TODO:
        1. [done] Error handling + account verification for sign up
        2. Return additional match info in response
        3. Return "End Game Screen" Level details for match info
        4. [done] Integrate with Deadlock GC (Phase B: GcClient / GcMatchSource)
        5. Ephemeral responses for Commands in the future
 */

suspend fun main() = coroutineScope {
    DatabaseFactory.init()
    val kord = Kord(Env.DISCORD_TOKEN)
    registerCommands(kord)
    handleCommands(kord)

    val gcProvider: MatchHistoryProvider? = buildGcProvider()
    launch {
        startScheduler(kord, gcProvider)
    }
    kord.login {
        presence { playing("tracking Deadlock matches") }
    }
}

/**
 * Builds the Deadlock GC match provider when a Steam bot account is configured
 * (username + either a refresh token or a password). Returns null otherwise, so
 * the scheduler falls back to the active-feed source.
 */
private fun buildGcProvider(): MatchHistoryProvider? {
    val username = Env.STEAM_USERNAME ?: return null
    if (Env.STEAM_PASSWORD == null && Env.STEAM_REFRESH_TOKEN == null) {
        println("STEAM_USERNAME set but no STEAM_PASSWORD or STEAM_REFRESH_TOKEN; skipping GC source.")
        return null
    }
    return GcClient(
        username = username,
        password = Env.STEAM_PASSWORD,
        initialRefreshToken = Env.STEAM_REFRESH_TOKEN,
    ).also { it.start() }
}