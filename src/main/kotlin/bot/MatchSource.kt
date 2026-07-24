package bot

import api.DeadlockClient
import models.ActiveMatchDTO

/** A match that a tracked account has just finished. */
data class FinishedMatch(val accountId: String, val matchId: Long)

/**
 * Detects matches that tracked accounts have finished since the last poll.
 *
 * This is the seam between the scheduler and whatever data backend supplies
 * match information. Phase A implements it with the deadlock-api active feed
 * ([ActiveFeedMatchSource]); a Phase B Game Coordinator source can implement
 * the same interface and drop in without changing the scheduler or embeds.
 */
interface MatchSource {
    /** Returns matches finished since the previous call for any of [trackedAccountIds]. */
    suspend fun pollFinished(trackedAccountIds: Set<String>): List<FinishedMatch>
}

/**
 * [MatchSource] backed by `/v1/matches/active`. Keeps an in-memory view of which
 * tracked account is currently in which live match; when an account leaves that
 * match (or moves to a new one), the previous match is reported as finished.
 *
 * The live view is per-process (rebuilt on restart), so a match that finishes
 * while the bot is down is not detected here — that gap is what a Phase B GC
 * source would close. The scheduler additionally de-dupes against the persisted
 * `last_match_id`, so a restart never re-posts the same match.
 */
class ActiveFeedMatchSource(private val client: DeadlockClient) : MatchSource {

    private val liveByAccount = mutableMapOf<String, Long>()

    override suspend fun pollFinished(trackedAccountIds: Set<String>): List<FinishedMatch> {
        val active = try {
            client.getActiveMatches()
        } catch (e: Exception) {
            // Preserve state and report nothing: treating a failed fetch as "no
            // live matches" would falsely flag every in-progress match as finished.
            println("active-matches poll failed, keeping prior state: ${e.message}")
            return emptyList()
        }
        return reconcile(active, trackedAccountIds)
    }

    /**
     * Pure diff of the current live feed against the previous one. Updates the
     * internal live view and returns matches that just finished. Exposed for
     * unit testing without any network access.
     */
    internal fun reconcile(active: List<ActiveMatchDTO>, trackedAccountIds: Set<String>): List<FinishedMatch> {
        val currentLive = HashMap<String, Long>()
        for (match in active) {
            for (player in match.players) {
                val account = player.accountId.toString()
                if (account in trackedAccountIds) currentLive[account] = match.matchId
            }
        }

        val finished = mutableListOf<FinishedMatch>()
        for ((account, previousMatchId) in liveByAccount) {
            if (account !in trackedAccountIds) continue
            // Still in the same match ⇒ not finished. Gone, or already in a new
            // match ⇒ the previous one finished.
            if (currentLive[account] != previousMatchId) {
                finished.add(FinishedMatch(account, previousMatchId))
            }
        }

        liveByAccount.clear()
        liveByAccount.putAll(currentLive)
        return finished
    }
}
