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
    @SerialName("stats") val stats: List<Stats> = emptyList(),
    // Per-player summary fields (also present on the /metadata endpoint). Defaulted
    // so partial responses still deserialize.
    @SerialName("team") val team: Int = 0,
    @SerialName("hero_id") val heroId: Int = 0,
    @SerialName("kills") val kills: Int = 0,
    @SerialName("deaths") val deaths: Int = 0,
    @SerialName("assists") val assists: Int = 0,
    @SerialName("net_worth") val netWorth: Int = 0,
    @SerialName("last_hits") val lastHits: Int = 0,
    @SerialName("denies") val denies: Int = 0,
    @SerialName("level") val heroLevel: Int = 0,
    @SerialName("abandon_match_time_s") val abandonedTimeSeconds: Int? = null,
)


@Serializable
data class MatchInfoDTO(
    @SerialName("average_badge_team0") val averageBadgeTeam0: Int = 0,
    @SerialName("average_badge_team1") val averageBadgeTeam1: Int = 0,
    @SerialName("match_id") val matchId: Long = 0,
    @SerialName("start_time") val startTime: Int = 0,
    // Nullable: live matches report null here; only populated once finished.
    @SerialName("duration_s") val durationSeconds: Int? = null,
    @SerialName("winning_team") val winningTeam: Int? = null,
    @SerialName("game_mode") val gameMode: Int = 0,
    @SerialName("match_mode") val matchMode: Int = 0,
    @SerialName("objectives_mask_team0") val objectivesMaskTeam0: Long = 0,
    @SerialName("objectives_mask_team1") val objectivesMaskTeam1: Long = 0,
    @SerialName(value = "players") val players: List<Players>
)


@Serializable
data class MatchDTO(
    @SerialName("match_info") val matchInfo : MatchInfoDTO
)

/**
 * Reconstructs a [MatchHistoryDTO] for a single player from full match metadata.
 * This lets the match-centric scheduler reuse the existing match-summary embed
 * without touching the stale per-player history endpoint. Optional API fields
 * default when absent; Valve metadata supplies modes, level and objective masks.
 *
 * Win/loss follows the existing convention in `MatchMessageGenerator`
 * (`matchResult == playerTeam` ⇒ win): here `matchResult` is the winning team
 * and `playerTeam` is the player's team, so they are equal exactly on a win.
 * Returns null if the account isn't in the match or the id can't be parsed.
 */
fun MatchDTO.toMatchHistory(accountId: String): MatchHistoryDTO? {
    val acct = accountId.toLongOrNull()?.takeIf { it in 1..4294967295L }?.toInt() ?: return null
    val info = matchInfo
    val player = info.players.firstOrNull { it.accountId == acct } ?: return null
    return MatchHistoryDTO(
        accountId = player.accountId,
        denies = player.denies,
        gameMode = info.gameMode,
        heroId = player.heroId,
        heroLevel = player.heroLevel,
        lastHits = player.lastHits,
        matchDurationSeconds = info.durationSeconds ?: 0,
        matchId = info.matchId,
        matchMode = info.matchMode,
        matchResult = info.winningTeam ?: -1,
        netWorth = player.netWorth,
        objectivesMaskTeam0 = info.objectivesMaskTeam0,
        objectivesMaskTeam1 = info.objectivesMaskTeam1,
        assists = player.assists,
        deaths = player.deaths,
        kills = player.kills,
        playerTeam = player.team,
        startTime = info.startTime,
        abandonedTimeSeconds = player.abandonedTimeSeconds,
    )
}
