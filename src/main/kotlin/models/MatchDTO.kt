package models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MatchInfoDTO(
    @SerialName("average_badge_team0") val averageBadgeTeam0: Int,
    @SerialName("average_badge_team1") val averageBadgeTeam1: Int
)

@Serializable
data class MatchDTO(
    @SerialName("match_info") val matchInfo : MatchInfoDTO
)