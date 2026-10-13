package bot

import api.gc.MatchHistoryProvider
import models.MatchDTO
import models.MatchHistoryDTO
import models.toMatchHistory

internal data class RecentMatches(
    val matches: List<MatchHistoryDTO>,
    val metadata: Map<Long, MatchDTO>,
    val unavailableIds: List<Long>,
    val latestId: Long?,
)

internal class RecentMatchLookup(
    private val gc: MatchHistoryProvider?,
    private val history: suspend (String) -> List<MatchHistoryDTO>,
    private val metadata: suspend (Long) -> MatchDTO?,
) {
    suspend fun lookup(accountId: String, count: Int): RecentMatches {
        require(count in 1..5)
        if (gc == null) {
            val matches = history(accountId).take(count)
            return RecentMatches(matches, emptyMap(), emptyList(), matches.firstOrNull()?.matchId)
        }
        val snapshot = gc.recentHistory(accountId)
        val ids = snapshot.ids.distinct().take(count)
        val summaries = snapshot.summaries.associateBy { it.matchId }
        val matches = mutableListOf<MatchHistoryDTO>()
        val details = mutableMapOf<Long, MatchDTO>()
        val unavailable = mutableListOf<Long>()
        for (id in ids) {
            val summary = summaries[id] ?: gc.cachedMatch(accountId, id)
            if (summary != null) {
                matches += summary
                continue
            }
            val info = metadata(id)
            val match = info?.takeIf { it.matchInfo.matchId == id }?.toMatchHistory(accountId)
            if (match == null) unavailable += id
            else {
                matches += match
                details[id] = requireNotNull(info)
            }
        }
        return RecentMatches(matches, details, unavailable, ids.firstOrNull())
    }
}
