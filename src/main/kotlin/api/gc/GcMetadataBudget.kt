package api.gc

/**
 * Rolling-window budget for fresh GC metadata queries.
 *
 * deadlock-api's ingest tool budgets 40 queries per account per rolling 24 hours, at least 20 seconds apart,
 * which suggests Valve counts queries per day rather than requiring long gaps. Uniform 30-minute spacing
 * delayed the second of two matches that finished together by up to half an hour.
 *
 * Sent queries are persisted so restarts cannot overspend. Part of the window is reserved for tracked
 * players' matches, so ad hoc lookups of other matches cannot exhaust it.
 */
class GcMetadataBudget(
    private val limit: Int = 40,
    private val windowMillis: Long = 24 * 60 * 60_000L,
    private val minIntervalMillis: Long = 20_000L,
    private val reserved: Int = limit / 4,
    private val load: () -> List<Long> = { emptyList() },
    private val record: (Long) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init { require(limit > 0 && windowMillis > 0 && minIntervalMillis >= 0 && reserved in 0 until limit) }

    private val sent: ArrayDeque<Long> by lazy {
        ArrayDeque(runCatching(load).onFailure { println("GC metadata budget history unavailable: ${it.message}") }
            .getOrDefault(emptyList()).sorted())
    }
    private var pausedUntil = 0L
    private var rateLimits = 0

    /** Zero when a query may be sent now; otherwise how long until one may be. */
    @Synchronized fun waitMillis(tracked: Boolean): Long {
        val now = clock()
        while (sent.isNotEmpty() && sent.first() <= now - windowMillis) sent.removeFirst()
        val capacity = if (tracked) limit else limit - reserved
        val untilQuota = if (sent.size < capacity) 0L else sent[sent.size - capacity] + windowMillis - now
        val untilInterval = sent.lastOrNull()?.let { it + minIntervalMillis - now } ?: 0L
        return maxOf(untilQuota, untilInterval, pausedUntil - now, 0L)
    }

    @Synchronized fun remaining(tracked: Boolean): Int {
        waitMillis(tracked)
        return ((if (tracked) limit else limit - reserved) - sent.size).coerceAtLeast(0)
    }

    /** Count the query as soon as it is sent, whatever Valve answers. */
    @Synchronized fun consume() {
        val now = clock()
        sent.addLast(now)
        try { record(now) } catch (e: Exception) { println("Could not persist GC metadata budget: ${e.message}") }
    }

    /** Valve's answer means its quota is spent; pause metadata queries for an hour, then 6 and 24 hours. */
    @Synchronized fun rateLimited(): Long {
        rateLimits = (rateLimits + 1).coerceAtMost(3)
        val pause = when (rateLimits) { 1 -> 60 * 60_000L; 2 -> 6 * 60 * 60_000L; else -> windowMillis }
        pausedUntil = clock() + pause
        println("Valve rate limited GC metadata; pausing fresh metadata queries for ${pause / 60_000} minutes.")
        return pause
    }

    @Synchronized fun succeeded() { rateLimits = 0 }
}
