package models


import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URL

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

object RankRepository {
    val json = URL("https://assets.deadlock-api.com/v2/ranks").readText()
    val ranks = Json {
        ignoreUnknownKeys = true
    }.decodeFromString<List<Rank>>(json)


    fun getRankImage(matchHistory: MatchHistoryDTO, additionalMatchInfo: MatchDTO?): String? {
        val avgTeamRank = when (matchHistory.playerTeam) {
            1 -> additionalMatchInfo?.matchInfo?.averageBadgeTeam1 ?: 0
            0 -> additionalMatchInfo?.matchInfo?.averageBadgeTeam0 ?: 0
            else -> 0
        }
        return rankImageForBadge(avgTeamRank, ranks)
    }

    // Pure and bounds-safe so it can be unit tested without a network call, and so an
    // out-of-range badge value can't throw IndexOutOfBoundsException in a command handler.
    fun rankImageForBadge(avgTeamRank: Int, ranks: List<Rank>): String? {
        val tier = avgTeamRank / 10
        val subrank = avgTeamRank % 10
        val rank = ranks.getOrNull(tier) ?: return null
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
