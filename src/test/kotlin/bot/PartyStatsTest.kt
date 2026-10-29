package bot

import models.*
import kotlin.test.*

class PartyStatsTest {
    private fun match(id: Long, account: Int, team: Int = 0, result: Int = 0, hero: Int = 1) =
        MatchDTO(MatchInfoDTO(matchId = id, durationSeconds = 600, winningTeam = result,
            players = listOf(Players(account, team = team, heroId = hero, kills = 8, deaths = 2, assists = 5))))
            .toMatchHistory(account.toString())!!
    @Test fun `only shared teammates count once with known and consistent results`() {
        val own = listOf(match(1, 1), match(2, 1), match(3, 1, result = -1), match(4, 1))
        val friend = PartyMember("2", "friend", listOf(match(1, 2, hero = 8), match(1, 2),
            match(2, 2, team = 1), match(3, 2), match(4, 2, result = 1)))
        val result = partyResults(own, listOf(friend)).single()
        assertEquals(1, result.games)
        assertEquals(1, result.wins)
        assertEquals(1 to 8, result.combo)
        assertEquals(8.0, result.kills)
    }
    @Test fun `empty reports explain limits and names cannot create mentions`() {
        assertContains(partyReport(emptyList()) { "Hero $it" }, "No shared")
        val r = partyResults(listOf(match(1, 1)), listOf(PartyMember("2", "@everyone", listOf(match(1, 2)))))
        assertFalse(partyReport(r) { "Hero $it" }.contains("@everyone"))
    }
}
