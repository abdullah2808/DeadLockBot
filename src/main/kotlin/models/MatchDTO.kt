package models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class Stats (
    @SerialName("player_damage") val playerDamage: Int,
    @SerialName("boss_damage") val objectiveDamage: Int,
    @SerialName("player_healing") val playerHealing: Int,
    @SerialName("damage_absorbed") val damageAbsorbed: Int
)

@Serializable
data class Players (
    @SerialName("account_id") val accountId: Int,
    @SerialName("stats") val stats: List<Stats>,
)


@Serializable
data class MatchInfoDTO(
    @SerialName("average_badge_team0") val averageBadgeTeam0: Int,
    @SerialName("average_badge_team1") val averageBadgeTeam1: Int,
    @SerialName(value = "players") val players: List<Players>
)


@Serializable
data class MatchDTO(
    @SerialName("match_info") val matchInfo : MatchInfoDTO
)