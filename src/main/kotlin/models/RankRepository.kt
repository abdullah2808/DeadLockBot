package models


import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RankImages(
    @SerialName("small_subrank1") val smallSubRankOne: String? = null,
    @SerialName("small_subrank2") val smallSubRankTwo: String? = null,
    @SerialName("small_subrank3") val smallSubRankThree: String? = null,
    @SerialName("small_subrank4") val smallSubRankFour: String? = null,
    @SerialName("small_subrank5") val smallSubRankFive: String? = null,
    @SerialName("small_subrank6") val smallSubRankSix: String? = null,
)
@Serializable
data class Rank(
    val tier: Int,
    val name: String,
    val images: RankImages? = null
)

data class RankBadge(val name: String, val subrank: Int, val image: String?) {
    val label get() = if (subrank in 1..6) "$name $subrank" else name
}

data class MatchRanks(val overall: Int?, val team0: Int?, val team1: Int?)

/**
 * Valve's team averages were 0 in the real ranked matches checked, so prefer averaging the players' own badges.
 * Badges aren't linear (116 is followed by 121), so average their position on the ladder instead.
 */
fun matchRanks(info: MatchInfoDTO): MatchRanks {
    fun average(players: List<Players>, reported: Int): Int? {
        val ladder = players.mapNotNull { it.rankData?.initialDisplayRank }.filter { it / 10 > 0 && it % 10 in 1..6 }
            .map { (it / 10) * 6 + it % 10 - 1 }
        if (ladder.isEmpty()) return reported.takeIf { it > 0 }
        val position = (ladder.sum() + ladder.size / 2) / ladder.size
        return (position / 6) * 10 + position % 6 + 1
    }
    return MatchRanks(average(info.players, 0), average(info.players.filter { it.team == 0 }, info.averageBadgeTeam0),
        average(info.players.filter { it.team == 1 }, info.averageBadgeTeam1))
}

object RankRepository {
    private val catalog = AssetCatalog(load = {
        assetJson.decodeFromString<List<Rank>>(readAssetCatalog("ranks")).associateBy { it.tier }
    })


    fun getRankImage(matchHistory: MatchHistoryDTO, additionalMatchInfo: MatchDTO?): String? {
        val ranks = additionalMatchInfo?.matchInfo?.let(::matchRanks)
        val avgTeamRank = when (matchHistory.playerTeam) {
            1 -> ranks?.team1 ?: 0
            0 -> ranks?.team0 ?: 0
            else -> 0
        }
        val rank = catalog.get(avgTeamRank / 10) ?: return null
        return rankImageForBadge(avgTeamRank, listOf(rank))
    }

    /** Name, subrank and image for a packed badge (tier * 10 + subrank); null when the tier is unknown. */
    fun describeBadge(badge: Int): RankBadge? = catalog.get(badge / 10)?.let { describeBadge(badge, listOf(it)) }

    fun describeBadge(badge: Int, ranks: List<Rank>): RankBadge? {
        val rank = ranks.firstOrNull { it.tier == badge / 10 } ?: return null
        return RankBadge(rank.name, badge % 10, rankImageForBadge(badge, ranks))
    }

    // Pure and bounds-safe so it can be unit tested without a network call, and so an
    // out-of-range badge value can't throw IndexOutOfBoundsException in a command handler.
    fun rankImageForBadge(avgTeamRank: Int, ranks: List<Rank>): String? {
        val tier = avgTeamRank / 10
        val subrank = avgTeamRank % 10
        val rank = ranks.firstOrNull { it.tier == tier } ?: return null
        return when (subrank) {
            1 -> rank.images?.smallSubRankOne
            2 -> rank.images?.smallSubRankTwo
            3 -> rank.images?.smallSubRankThree
            4 -> rank.images?.smallSubRankFour
            5 -> rank.images?.smallSubRankFive
            6 -> rank.images?.smallSubRankSix
            else -> null
        }
    }
}
