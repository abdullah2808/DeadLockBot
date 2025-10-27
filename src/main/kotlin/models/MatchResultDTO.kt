package models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MatchResultDTO(
    @SerialName("account_id") val accountId: Int,
    val denies: Int,
    @SerialName("game_mode") val gameMode: Int,
    @SerialName("hero_id") val heroId: Int,
    @SerialName("hero_level") val heroLevel: Int,
    @SerialName("last_hits") val lastHits: Int,
    @SerialName("match_duration_s") val matchDurationSeconds: Int,
    @SerialName("match_id") val matchId: Long,
    @SerialName("match_mode") val matchMode: Int,
    @SerialName("match_result") val matchResult: Int,
    @SerialName("net_worth") val netWorth: Int,
    @SerialName("objectives_mask_team0") val objectivesMaskTeam0: Int,
    @SerialName("objectives_mask_team1") val objectivesMaskTeam1: Int,
    @SerialName("player_assists") val assists: Int,
    @SerialName("player_deaths") val deaths: Int,
    @SerialName("player_kills") val kills: Int,
    @SerialName("player_team") val playerTeam: Int,
    @SerialName("start_time") val startTime: Int,
    @SerialName("abandoned_time_s") val abandonedTimeSeconds: Int? = null,
    @SerialName("team_abandoned") val teamAbandoned: Boolean? = null,
    val username: String? = null
)