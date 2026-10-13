package bot

/** Separate notification deadlines from source polling; neither waiting nor retries reset discovery. */
internal class NotificationTiming(
    private val gracePeriodMillis: Long = 60_000L,
    private val pollIntervalMillis: Long = 90_000L,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    init { require(gracePeriodMillis >= 0 && pollIntervalMillis > 0) }
    private val notifyAt = mutableMapOf<FinishedMatch, Long>()
    private var nextPollAt = clock()

    fun pollDue(): Boolean = clock() >= nextPollAt
    fun pollFinished() { nextPollAt = clock() + pollIntervalMillis }
    fun discovered(match: FinishedMatch) { notifyAt.putIfAbsent(match, clock() + gracePeriodMillis) }
    fun ready(match: FinishedMatch): Boolean = notifyAt[match]?.let { clock() >= it } ?: false
    fun forget(match: FinishedMatch) { notifyAt.remove(match) }

    /** Ready entries have already had their turn; keep failed sends on the normal poll cadence. */
    fun nextWakeDelayMillis(): Long {
        val now = clock()
        val untilPoll = (nextPollAt - now).coerceAtLeast(1)
        val untilNotification = notifyAt.values.filter { it > now }.minOrNull()?.minus(now)
        return minOf(untilPoll, untilNotification ?: untilPoll)
    }
}
