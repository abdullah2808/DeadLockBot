package bot

internal class MetadataRetrySchedule(private val clock: () -> Long = System::currentTimeMillis) {
    private val retryAt = mutableMapOf<FinishedMatch, Long>()

    fun ready(match: FinishedMatch): Boolean = clock() >= (retryAt[match] ?: 0L)

    fun defer(match: FinishedMatch, attempt: Int): Long {
        val wait = (90_000L * (1L shl (attempt - 1).coerceIn(0, 4))).coerceAtMost(15 * 60_000L)
        retryAt[match] = clock() + wait
        return wait
    }

    fun forget(match: FinishedMatch) { retryAt.remove(match) }
}

internal fun deferPendingMetadata(
    match: FinishedMatch,
    pending: MutableMap<FinishedMatch, Int>,
    retries: MetadataRetrySchedule,
): Long {
    val attempt = ((pending[match] ?: 0) + 1).coerceAtMost(1000)
    pending[match] = attempt
    return retries.defer(match, attempt)
}
