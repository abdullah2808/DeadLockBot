package api.gc

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

class GcRateLimitedException(val retryAfterMillis: Long) :
    IOException("Valve GC rate limited; retry in ${(retryAfterMillis + 999) / 1000}s")

class GcRequestDeferredException(val retryAfterMillis: Long) :
    IOException("GC metadata query budget defers this lookup; retry in ${(retryAfterMillis + 999) / 1000}s")

/** Serialize queries and apply a session-wide cooldown before sending another GC request. */
internal class GcRequestGate(
    private val budgets: Map<Int, GcMetadataBudget> = emptyMap(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutex = Mutex()
    private var retryAt = Long.MIN_VALUE
    private var attempts = 0
    private var limitedOperation: Int? = null

    /**
     * [fetch] calls its argument immediately before the message leaves this process. Only then is the
     * budget spent: a session that never became ready must not use a scarce query. [tracked] queries,
     * for registered players' matches, may use the budget's reserved share.
     */
    suspend fun <T> request(operation: Int, result: (T) -> Int, tracked: Boolean = true,
                            fetch: suspend (sending: () -> Unit) -> T): T = mutex.withLock {
        val now = clock()
        if (now < retryAt) throw GcRateLimitedException(retryAt - now)
        val budget = budgets[operation]
        budget?.waitMillis(tracked)?.takeIf { it > 0 }?.let { throw GcRequestDeferredException(it) }
        // Count once sent; even a timeout or denied request may count against Valve's quota.
        val response = fetch { budget?.consume() }
        when (result(response)) {
            5 -> { // Both Deadlock history and metadata enums use 5 for RateLimited.
                attempts = (attempts + 1).coerceAtMost(5)
                val delay = (60_000L shl (attempts - 1)).coerceAtMost(15 * 60_000L)
                retryAt = clock() + delay
                limitedOperation = operation
                println("Valve GC rate limited; pausing history and metadata queries for ${delay / 1000}s.")
                throw GcRateLimitedException(maxOf(delay, budget?.rateLimited() ?: 0L))
            }
            1 -> {
                budget?.succeeded()
                if (limitedOperation == operation) {
                    // Success from a different endpoint must not erase repeated throttling.
                    attempts = 0
                    limitedOperation = null
                }
            }
        }
        response
    }
}
