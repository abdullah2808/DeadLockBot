package models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MatchDtoMappingTest {

    private fun metadata(winningTeam: Int?, vararg players: Players) =
        MatchDTO(
            MatchInfoDTO(
                matchId = 99L,
                startTime = 1_700_000_000,
                durationSeconds = 1800,
                winningTeam = winningTeam,
                players = players.toList()
            )
        )

    private fun player(accountId: Int, team: Int) = Players(
        accountId = accountId, team = team, heroId = 5,
        kills = 10, deaths = 2, assists = 7, netWorth = 25000, lastHits = 100, denies = 5
    )

    @Test
    fun `maps the player's row and marks a win when team equals winning team`() {
        val match = metadata(winningTeam = 1, player(107, team = 1), player(208, team = 0))

        val history = match.toMatchHistory("107")!!

        assertEquals(107, history.accountId)
        assertEquals(10, history.kills)
        assertEquals(2, history.deaths)
        assertEquals(7, history.assists)
        assertEquals(25000, history.netWorth)
        assertEquals(99L, history.matchId)
        // win convention: matchResult == playerTeam
        assertEquals(history.playerTeam, history.matchResult)
    }

    @Test
    fun `marks a loss when the player's team did not win`() {
        val match = metadata(winningTeam = 1, player(208, team = 0))

        val history = match.toMatchHistory("208")!!

        assertEquals(0, history.playerTeam)
        assertEquals(1, history.matchResult)
    }

    @Test
    fun `returns null when the account is not in the match`() {
        val match = metadata(winningTeam = 0, player(107, team = 0))

        assertNull(match.toMatchHistory("999"))
    }
}
