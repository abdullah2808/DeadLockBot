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
)
