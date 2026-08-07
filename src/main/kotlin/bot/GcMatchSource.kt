package bot

import api.gc.MatchHistoryProvider

/**
 * [MatchSource] backed by the Deadlock Game Coordinator ([MatchHistoryProvider]).
 * For each tracked account it asks the GC for the newest match id and reports it
 * as finished when it changes since the last poll.
 *
 * Unlike [ActiveFeedMatchSource], the GC returns a player's *own* complete
 * history, so this covers every match (not just the top-150 spectated feed) and
 * catches matches played while the bot was offline: on the first poll after
 * startup the newest id is emitted and the scheduler's `last_match_id` de-dupe
 * decides whether it still needs posting.
 */
class GcMatchSource(private val provider: MatchHistoryProvider) : MatchSource {

    private val lastSeen = mutableMapOf<String, Long>()

    override suspend fun pollFinished(trackedAccountIds: Set<String>): List<FinishedMatch> {
        val finished = mutableListOf<FinishedMatch>()
        for (accountId in trackedAccountIds) {
            val newest = try {
                provider.recentMatches(accountId).firstOrNull()
            } catch (e: Exception) {
                println("GC match-history lookup failed for $accountId: ${e.message}")
                null
            } ?: continue

            if (lastSeen[accountId] != newest.matchId) {
                // Carry the GC summary so the scheduler can post immediately.
                finished.add(FinishedMatch(accountId, newest.matchId, newest))
                lastSeen[accountId] = newest.matchId
            }
        }
        // Forget accounts that are no longer tracked.
        lastSeen.keys.retainAll(trackedAccountIds)
        return finished
    }
}
