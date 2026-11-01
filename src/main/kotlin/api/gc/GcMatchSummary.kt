package api.gc

import gc.citadel.CitadelMatchHistory.CMsgClientToGCGetMatchHistoryResponse.Match
import models.MatchHistoryDTO

internal fun Match.toSummary(accountId: String): MatchHistoryDTO? {
    // Keep an incomplete row's ID for metadata fallback, without inventing its stats.
    if (!hasMatchId() || !hasHeroId() || !hasMatchDurationS() || !hasStartTime()) return null
    val account = accountId.toLongOrNull()?.takeIf { it in 0..0xffff_ffffL } ?: return null
    val result = when {
        hasPlayerMatchOutcome() -> when (playerMatchOutcome) {
            1 -> playerTeam
            2 -> if (playerTeam == 0) 1 else 0
            else -> -1
        }
        hasMatchResult() && matchResult in 0..1 -> matchResult
        else -> -1
    }
    return MatchHistoryDTO(
        accountId = account.toInt(), denies = denies, gameMode = gameMode,
        heroId = heroId, heroLevel = heroLevel, lastHits = lastHits,
        matchDurationSeconds = matchDurationS, matchId = matchId, matchMode = matchMode,
        matchResult = result, netWorth = netWorth,
        objectivesMaskTeam0 = objectivesMaskTeam0, objectivesMaskTeam1 = objectivesMaskTeam1,
        assists = playerAssists, deaths = playerDeaths, kills = playerKills,
        playerTeam = playerTeam, startTime = startTime,
        abandonedTimeSeconds = abandonedTimeS.takeIf { hasAbandonedTimeS() },
        teamAbandoned = teamAbandoned.takeIf { hasTeamAbandoned() },
        rankedDisplayBadge = rankedDisplayBadge.takeIf { hasRankedDisplayBadge() },
        rankedDelta = rankedDelta.takeIf { hasRankedDelta() },
        rankedCalibrationMatch = rankedCalibrationMatch.takeIf { hasRankedCalibrationMatch() },
        rankedUsedDemotionProtection = rankedUsedDemotionProtection.takeIf { hasRankedUsedDemotionProtection() },
    )
}
