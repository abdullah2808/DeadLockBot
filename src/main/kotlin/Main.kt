import api.gc.GcClient
import api.gc.CachedMatchHistoryProvider
import api.gc.MatchHistoryProvider
import bot.handleCommands
import bot.registerCommands
import bot.startScheduler
import bot.acceptNullableChannelApplicationIds
import data.DatabaseFactory
import data.MatchCacheRepository
import api.DeadlockClient
import api.MatchMetadataService
import api.PlayerNameService
import api.SteamAccountResolver
import api.gc.ValveMetadataClient
import data.UserRepository
import dev.kord.core.Kord
import dev.kord.gateway.DefaultGateway
import dev.kord.gateway.ratelimit.IdentifyRateLimiter
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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
    // The shared service persists GC and API results through the same participant-aware cache.
    DeadlockClient.configureMetadataCache(MatchCacheRepository::findMetadata) {}
    val kord = Kord(Env.DISCORD_TOKEN) {
        gateways { resources, shards ->
            val limiter = IdentifyRateLimiter(resources.maxConcurrency, defaultDispatcher)
            shards.map {
                DefaultGateway {
                    client = resources.httpClient
                    identifyRateLimiter = limiter
                }.acceptNullableChannelApplicationIds()
            }
        }
    }
    registerCommands(kord)
    val gcProvider: MatchHistoryProvider? = buildGcProvider()
    gcProvider?.updateTrackedAccounts(UserRepository.getAllUsers().map { it.accountId }.toSet())
    val apiClient = DeadlockClient()
    val valveClient = ValveMetadataClient()
    val metadata = MatchMetadataService(gcProvider, valveClient::download, apiClient::getMatchByMatchID,
        find = MatchCacheRepository::findMetadata,
        save = { MatchCacheRepository.saveEnrichedMetadata(it, UserRepository.getAllUsers().map { user -> user.accountId }.toSet()) },
        knownMatch = { account, match -> MatchCacheRepository.find(account, match) != null })
    val profileResolver = SteamAccountResolver()
    val playerNames = PlayerNameService(steamName = { gcProvider?.steamPlayerName(it) }, profileName = profileResolver::playerName)
    handleCommands(kord, gcProvider, metadata, playerNames)
    val scheduler = launch { startScheduler(kord, gcProvider, metadata) }
    try {
        kord.login { presence { playing("tracking Deadlock matches") } }
    } finally {
        withContext(NonCancellable) {
            scheduler.cancelAndJoin()
            gcProvider?.close()
            valveClient.close()
            profileResolver.close()
            apiClient.close()
        }
    }
}

/**
 * Builds the Deadlock GC match provider when a Steam bot account is configured
 * (username + either a refresh token or a password). Returns null otherwise, so
 * the scheduler falls back to the active-feed source.
 */
private fun buildGcProvider(): MatchHistoryProvider? {
    val username = Env.STEAM_USERNAME ?: return null
    return CachedMatchHistoryProvider(GcClient(
        username = username,
        password = Env.STEAM_PASSWORD,
        initialRefreshToken = Env.STEAM_REFRESH_TOKEN,
        tokenFile = java.io.File(Env.STEAM_TOKEN_FILE),
        autoAcceptFriends = Env.STEAM_AUTO_ACCEPT_FRIENDS,
    ).also { it.start() })
}
