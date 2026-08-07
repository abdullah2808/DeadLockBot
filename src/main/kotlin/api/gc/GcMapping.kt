package api.gc

import gc.citadel.CitadelMatchHistory
import models.MatchHistoryDTO

/**
 * Maps a Game Coordinator match-history entry to the app's [MatchHistoryDTO],
 * so a match can be posted straight from GC data without waiting for
 * deadlock-api to ingest the match metadata.
 *
 * `accountId` is the Steam3 id the history was requested for (the GC entry
 * itself doesn't carry it). Win/loss follows the existing convention
 * (`matchResult == playerTeam` ⇒ win): the GC reports `match_result` as the
 * winning team and `player_team` as the player's team.
 */
fun CitadelMatchHistory.CMsgClientToGCGetMatchHistoryResponse.Match.toMatchHistory(
    accountId: Int,
): MatchHistoryDTO = MatchHistoryDTO(
    accountId = accountId,
    denies = denies,
    gameMode = gameMode,
    heroId = heroId,
    heroLevel = heroLevel,
    lastHits = lastHits,
    matchDurationSeconds = matchDurationS,
    matchId = matchId,
    matchMode = matchMode,
    matchResult = matchResult,
    netWorth = netWorth,
    objectivesMaskTeam0 = objectivesMaskTeam0.toInt(),
    objectivesMaskTeam1 = objectivesMaskTeam1.toInt(),
    assists = playerAssists,
    deaths = playerDeaths,
    kills = playerKills,
    playerTeam = playerTeam,
    startTime = startTime,
    abandonedTimeSeconds = if (hasAbandonedTimeS()) abandonedTimeS else null,
    teamAbandoned = if (hasTeamAbandoned()) teamAbandoned else null,
)
