package api.gc

import gc.citadel.CitadelMatchHistory.CMsgClientToGCGetMatchHistoryResponse.Match
import kotlin.test.*

class GcMatchSummaryTest {
    private fun row(outcome: Int) = Match.newBuilder().setMatchId(115696167).setHeroId(88)
        .setMatchDurationS(2174).setStartTime(1_700_000_000).setPlayerTeam(1)
        .setPlayerKills(7).setPlayerDeaths(6).setPlayerAssists(22).setNetWorth(40281)
        .setPlayerMatchOutcome(outcome).setObjectivesMaskTeam0(1L shl 40).build()

    @Test
    fun `GC summary includes basic stats without external metadata`() {
        val match = row(1).toSummary("107")!!
        assertEquals(115696167L, match.matchId)
        assertEquals(88, match.heroId)
        assertEquals(7, match.kills)
        assertEquals(40281, match.netWorth)
        assertEquals(2174, match.matchDurationSeconds)
        assertEquals(match.playerTeam, match.matchResult)
        assertEquals(1L shl 40, match.objectivesMaskTeam0)
    }

    @Test
    fun `GC loss outcome maps independently of player team`() {
        assertNotEquals(1, row(2).toSummary("107")!!.matchResult)
    }

    @Test
    fun `unknown outcome is not displayed as a win`() {
        assertEquals(-1, row(5).toSummary("107")!!.matchResult)
    }

    @Test
    fun `omitted zero-valued stats follow the protocol defaults`() {
        val match = Match.newBuilder().setMatchId(99).setHeroId(88).setMatchDurationS(60)
            .setStartTime(123).setPlayerMatchOutcome(2).build().toSummary("107")!!
        assertEquals(0, match.kills)
        assertEquals(0, match.playerTeam)
        assertEquals(1, match.matchResult)
    }

    @Test
    fun `incomplete GC row does not invent player stats`() {
        assertNull(Match.newBuilder().setMatchId(99).build().toSummary("107"))
    }
}
