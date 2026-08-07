package api.gc

import gc.citadel.CitadelMatchHistory
import kotlin.test.Test
import kotlin.test.assertEquals

class GcMappingTest {

    private fun gcMatch(matchId: Long, playerTeam: Int, matchResult: Int) =
        CitadelMatchHistory.CMsgClientToGCGetMatchHistoryResponse.Match.newBuilder()
            .setMatchId(matchId)
            .setHeroId(5)
            .setMatchDurationS(1800)
            .setStartTime(1_700_000_000)
            .setMatchResult(matchResult)
            .setPlayerTeam(playerTeam)
            .setPlayerKills(10)
            .setPlayerDeaths(2)
            .setPlayerAssists(7)
            .setLastHits(100)
            .setDenies(5)
            .setHeroLevel(20)
            .setNetWorth(25000)
            .build()

    @Test
    fun `maps GC match fields onto MatchHistoryDTO`() {
        val dto = gcMatch(matchId = 99L, playerTeam = 1, matchResult = 1).toMatchHistory(accountId = 107757107)

        assertEquals(107757107, dto.accountId)
        assertEquals(99L, dto.matchId)
        assertEquals(5, dto.heroId)
        assertEquals(10, dto.kills)
        assertEquals(2, dto.deaths)
        assertEquals(7, dto.assists)
        assertEquals(25000, dto.netWorth)
        assertEquals(1800, dto.matchDurationSeconds)
    }

    @Test
    fun `win when player team equals winning team, loss otherwise`() {
        // Existing embed convention: matchResult == playerTeam => win.
        val win = gcMatch(matchId = 1L, playerTeam = 1, matchResult = 1).toMatchHistory(1)
        assertEquals(win.playerTeam, win.matchResult)

        val loss = gcMatch(matchId = 2L, playerTeam = 0, matchResult = 1).toMatchHistory(1)
        assertEquals(0, loss.playerTeam)
        assertEquals(1, loss.matchResult)
    }

    @Test
    fun `optional abandoned fields are null when unset`() {
        val dto = gcMatch(matchId = 3L, playerTeam = 0, matchResult = 0).toMatchHistory(1)
        assertEquals(null, dto.teamAbandoned)
        assertEquals(null, dto.abandonedTimeSeconds)
    }
}
