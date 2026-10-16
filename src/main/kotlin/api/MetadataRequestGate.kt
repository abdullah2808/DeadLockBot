package api

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import models.MatchDTO

/** deadlock-api's Steam-backed lookups are paced; callers can report when the next one may be sent. */
class MetadataDeferredException(val retryAfterMillis: Long) :
    IOException("deadlock-api Steam lookups paced; retry in ${(retryAfterMillis + 999) / 1000}s")

/**
 * Shared across clients so commands cannot bypass a metadata rate-limit cooldown.
 *
 * Stored lookups (`disable_steam=true`) and Steam-backed lookups have separate quotas at deadlock-api,
 * so a 429 from the scarce Steam-backed quota must not pause the plentiful stored lookups.
 */
class MetadataRequestGate(
    private val clock: () -> Long = System::currentTimeMillis,
    private val find: (Long) -> MatchDTO? = { null },
    private val save: (MatchDTO) -> Unit = {},
    private val steamIntervalMillis: Long = KEYLESS_STEAM_INTERVAL_MILLIS,
) {
    private inner class Lane(private val label: String) {
        val mutex = Mutex()
        @Volatile var retryAt = 0L
        private var rateLimits = 0

        fun limited(retryAfter: String?) {
            rateLimits = (rateLimits + 1).coerceAtMost(5)
            val fallback = (60_000L * (1L shl (rateLimits - 1))).coerceAtMost(15 * 60_000L)
            val wait = retryAfter?.toLongOrNull()?.takeIf { it in 0..86_400 }
                ?.times(1000L)
                ?: runCatching {
                    ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli() - clock()
                }.getOrNull()
                ?: fallback
            retryAt = clock() + wait.coerceAtLeast(1000L)
            println("$label rate limited; pausing requests for ${wait.coerceAtLeast(1000L) / 1000}s.")
        }

        fun succeeded() {
            rateLimits = 0
            retryAt = 0L
        }
    }

    private val stored = Lane("Match metadata")
    private val steam = Lane("Steam-backed match metadata")
    private var nextSteam = 0L
    private val cache = object : LinkedHashMap<Long, MatchDTO>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, MatchDTO>) = size > 256
    }

    suspend fun request(matchId: Long, block: suspend () -> MatchDTO?): MatchDTO? = stored.mutex.withLock {
        cached(matchId)?.let { return@withLock it }
        if (clock() < stored.retryAt) return@withLock null
        block()?.also { remember(matchId, it) }
    }

    /** Reserves the pacing interval before sending and throws [MetadataDeferredException] instead of waiting. */
    suspend fun requestFromSteam(matchId: Long, block: suspend () -> MatchDTO?): MatchDTO? = steam.mutex.withLock {
        cached(matchId)?.let { return@withLock it }
        val now = clock()
        // Load shedding on stored lookups means the whole service is struggling.
        val open = maxOf(nextSteam, steam.retryAt, stored.retryAt)
        if (now < open) throw MetadataDeferredException(open - now)
        nextSteam = now + steamIntervalMillis
        block()?.also { remember(matchId, it) }
    }

    fun rateLimited(retryAfter: String?) = stored.limited(retryAfter)
    fun succeeded() = stored.succeeded()
    fun steamRateLimited(retryAfter: String?) = steam.limited(retryAfter)
    fun steamSucceeded() = steam.succeeded()

    private fun cached(matchId: Long): MatchDTO? {
        synchronized(cache) { cache[matchId] }?.let { return it }
        return find(matchId)?.also { synchronized(cache) { cache[matchId] = it } }
    }

    private fun remember(matchId: Long, match: MatchDTO) {
        if (match.matchInfo.matchId == matchId && match.matchInfo.winningTeam != null && match.matchInfo.durationSeconds != null) {
            synchronized(cache) { cache[matchId] = match }
            save(match)
        }
    }

    companion object {
        /** deadlock-api allows three Steam-backed lookups per hour per IP without a key. */
        const val KEYLESS_STEAM_INTERVAL_MILLIS = 20 * 60_000L
        /** With a key the quota is 300 per hour; stay well below it. */
        const val KEYED_STEAM_INTERVAL_MILLIS = 15_000L
    }
}
