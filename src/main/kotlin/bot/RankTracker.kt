package bot

import api.gc.ProfileCard
import data.RankSnapshot
import data.RankSnapshotRepository
import kotlinx.coroutines.CancellationException

/** A badge change worth announcing; [previous] is null when the player had no visible rank before. */
data class RankChange(val previous: Int?, val current: Int) {
    enum class Direction { PLACED, UP, DOWN }
    val direction get() = when {
        previous == null -> Direction.PLACED
        current > previous -> Direction.UP
        else -> Direction.DOWN
    }
}

/**
 * Watches tracked players' profile-card badges. A ranked match schedules checks shortly afterwards (Valve may
 * apply the new rank with a delay); every account is also refreshed every few hours. Checks are spread out so
 * the shared GC session is never flooded, and the first sighting only records a baseline.
 */
internal class RankTracker(
    private val fetch: suspend (String) -> ProfileCard?,
    private val all: () -> Map<String, RankSnapshot> = RankSnapshotRepository::all,
    private val save: (String, RankSnapshot) -> Unit = RankSnapshotRepository::save,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The scheduler can wake every few seconds for metadata retries; rank checks need no such urgency. */
    private val minIntervalMs: Long = 30_000L,
) {
    companion object {
        val AFTER_MATCH_DELAYS_MS = listOf(2 * 60_000L, 15 * 60_000L)
        const val REFRESH_MS = 6 * 60 * 60_000L
        const val FAILURE_BACKOFF_MS = 10 * 60_000L
        const val CHECKS_PER_CYCLE = 2
    }
    private val scheduled = mutableMapOf<String, MutableList<Long>>()
    private val backoffUntil = mutableMapOf<String, Long>()
    private var lastRunAt: Long? = null

    fun rankedMatchFinished(accountId: String) {
        val now = clock()
        scheduled[accountId] = AFTER_MATCH_DELAYS_MS.map { now + it }.toMutableList()
    }

    /** Check up to [CHECKS_PER_CYCLE] due accounts and return the changes to announce. */
    suspend fun checkDue(accounts: Set<String>): List<Pair<String, RankChange>> {
        val now = clock()
        if (lastRunAt?.let { now - it < minIntervalMs } == true) return emptyList()
        lastRunAt = now
        scheduled.keys.retainAll(accounts)
        backoffUntil.keys.retainAll(accounts)
        val snapshots = all()
        val afterMatch = accounts.filter { account -> scheduled[account]?.firstOrNull()?.let { it <= now } == true }
        val stale = accounts.filter { it !in afterMatch && snapshots[it]?.let { s -> now - s.checkedAt >= REFRESH_MS } != false }
        val due = (afterMatch + stale).filter { (backoffUntil[it] ?: Long.MIN_VALUE) <= now }.take(CHECKS_PER_CYCLE)

        val changes = mutableListOf<Pair<String, RankChange>>()
        for (account in due) {
            val card = try { fetch(account) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                println("Rank check for $account failed: ${e.message}")
                null
            }
            if (card == null) {
                backoffUntil[account] = now + FAILURE_BACKOFF_MS
                continue
            }
            backoffUntil.remove(account)
            val previous = snapshots[account]
            val current = card.rankedBadgeLevel
            // A missing badge (hidden or not reported) keeps the last known rank rather than counting as a change.
            save(account, RankSnapshot(current ?: previous?.badge, now))
            val change = if (previous != null && current != null && current != previous.badge) RankChange(previous.badge, current) else null
            if (change != null) {
                scheduled.remove(account) // already moved; later post-match checks are unnecessary
                changes += account to change
                println("Rank change for $account: ${previous?.badge} -> $current.")
            } else {
                scheduled[account]?.let { if (it.firstOrNull()?.let { at -> at <= now } == true) it.removeAt(0) }
                if (scheduled[account]?.isEmpty() == true) scheduled.remove(account)
            }
        }
        return changes
    }
}
