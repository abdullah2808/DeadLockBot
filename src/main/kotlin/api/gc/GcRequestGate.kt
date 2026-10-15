package api.gc

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

class GcRateLimitedException(val retryAfterMillis: Long) :
    IOException("Valve GC rate limited; retry in ${(retryAfterMillis + 999) / 1000}s")

class GcRequestDeferredException(val retryAfterMillis: Long) :
    IOException("GC metadata query paced; retry in ${(retryAfterMillis + 999) / 1000}s")

/** Serialize queries and apply a session-wide cooldown before sending another GC request. */
internal class GcRequestGate(
    private val intervals: Map<Int, Long> = emptyMap(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutex = Mutex()
    private var retryAt = Long.MIN_VALUE
    private var attempts = 0
    private var limitedOperation: Int? = null
    private val nextQuery = mutableMapOf<Int, Long>()

    suspend fun <T> request(operation: Int, result: (T) -> Int, fetch: suspend () -> T): T = mutex.withLock {
        val now = clock()
        if (now < retryAt) throw GcRateLimitedException(retryAt - now)
        val due = nextQuery[operation] ?: Long.MIN_VALUE
        if (now < due) throw GcRequestDeferredException(due - now)
        // Reserve before sending; even a timeout or denied request may count against Valve's quota.
        intervals[operation]?.let { nextQuery[operation] = now + it }
        val response = fetch()
        when (result(response)) {
            5 -> { // Both Deadlock history and metadata enums use 5 for RateLimited.
                attempts = (attempts + 1).coerceAtMost(5)
                val delay = (60_000L shl (attempts - 1)).coerceAtMost(15 * 60_000L)
                retryAt = clock() + delay
                limitedOperation = operation
                println("Valve GC rate limited; pausing history and metadata queries for ${delay / 1000}s.")
                throw GcRateLimitedException(delay)
            }
            1 -> if (limitedOperation == operation) {
                // Success from a different endpoint must not erase repeated throttling.
                attempts = 0
                limitedOperation = null
            }
        }
        response
    }
}
