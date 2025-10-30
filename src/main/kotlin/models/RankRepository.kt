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


    fun getRankImage(matchHistory: MatchHistoryDTO, additionalMatchInfo: MatchDTO?) : String? {
        val avgTeamRank: Int
        when (matchHistory.playerTeam) {
            1 -> avgTeamRank = additionalMatchInfo?.matchInfo?.averageBadgeTeam1 ?: 0
            0 -> avgTeamRank = additionalMatchInfo?.matchInfo?.averageBadgeTeam1 ?: 0
            else -> avgTeamRank = 0
        }
        val tier = avgTeamRank / 10
        val subrank = avgTeamRank % 10
        return when (subrank) {
            1 -> ranks[tier].images?.smallSubRankOne
            2 -> ranks[tier].images?.smallSubRankTwo
            3 -> ranks[tier].images?.smallSubRankThree
            4 -> ranks[tier].images?.smallSubRankFour
            5 -> ranks[tier].images?.smallSubRankFive
            6 -> ranks[tier].images?.smallSubRankSix
            else -> ""
        }
    }
}
