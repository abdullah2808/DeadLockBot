package models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A player entry within a live match from `/v1/matches/active`.
 * `account_id` is a uint32 that can exceed Int range, so it is modelled as [Long].
 */
@Serializable
data class ActiveMatchPlayer(
    @SerialName("account_id") val accountId: Long,
    @SerialName("team") val team: Int = 0,
    @SerialName("hero_id") val heroId: Int = 0,
    // Nullable: the public feed reports null here for every player.
    @SerialName("abandoned") val abandoned: Boolean? = null,
)

/**
 * A currently-live match from `/v1/matches/active`. Unlike the per-player
 * match-history endpoint (whose index lags for quiet accounts), this feed is
 * fresh, so it is used to detect when a tracked player is in — and later
 * finishes — a match.
 */
@Serializable
data class ActiveMatchDTO(
    @SerialName("match_id") val matchId: Long,
    @SerialName("start_time") val startTime: Long = 0,
    @SerialName("players") val players: List<ActiveMatchPlayer> = emptyList(),
    // Nullable: the public feed reports null here for matches still in progress.
    @SerialName("duration_s") val durationS: Long? = null,
    @SerialName("spectators") val spectators: Long = 0,
    @SerialName("net_worth_team_0") val netWorthTeam0: Long = 0,
    @SerialName("net_worth_team_1") val netWorthTeam1: Long = 0,
    @SerialName("game_mode") val gameMode: Int = 0,
    @SerialName("match_mode") val matchMode: Int = 0,
)
