package api

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import models.MatchDTO

/** Shared across clients so commands cannot bypass a metadata rate-limit cooldown. */
class MetadataRequestGate(
    private val clock: () -> Long = System::currentTimeMillis,
    private val find: (Long) -> MatchDTO? = { null },
    private val save: (MatchDTO) -> Unit = {},
) {
    private val mutex = Mutex()
    private var retryAt = 0L
    private var rateLimits = 0
    private val cache = object : LinkedHashMap<Long, MatchDTO>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, MatchDTO>) = size > 256
    }

    suspend fun request(matchId: Long, block: suspend () -> MatchDTO?): MatchDTO? = mutex.withLock {
        cache[matchId]?.let { return@withLock it }
        find(matchId)?.let { cache[matchId] = it; return@withLock it }
        if (clock() < retryAt) return@withLock null
        block()?.also {
            if (it.matchInfo.matchId == matchId && it.matchInfo.winningTeam != null && it.matchInfo.durationSeconds != null) {
                cache[matchId] = it
                save(it)
            }
        }
    }

    fun rateLimited(retryAfter: String?) {
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
        println("Match metadata rate limited; pausing requests for ${wait.coerceAtLeast(1000L) / 1000}s.")
    }

    fun succeeded() {
        rateLimits = 0
        retryAt = 0L
    }
}
