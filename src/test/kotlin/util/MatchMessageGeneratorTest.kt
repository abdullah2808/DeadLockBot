package util

import models.HeroInfo
import models.MatchDTO
import models.MatchHistoryDTO
import models.MatchInfoDTO
import models.Players
import models.Stats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MatchMessageGeneratorTest {
    private val heroes: (Int) -> HeroInfo = { HeroInfo("Haze", null) }

    private fun match() = MatchHistoryDTO(
        accountId = 42, denies = 12, gameMode = 1, heroId = 13,
        heroLevel = 24, lastHits = 190, matchDurationSeconds = 1805,
        matchId = 123456, matchMode = 1, matchResult = 0, netWorth = 54321,
        objectivesMaskTeam0 = 0, objectivesMaskTeam1 = 0,
        assists = 20, deaths = 4, kills = 12, playerTeam = 0,
        startTime = 1700000000
    )

    @Test
    fun `missing and empty details still produce a complete notification`() {
        val details = MatchDTO(MatchInfoDTO(averageBadgeTeam0 = 0, averageBadgeTeam1 = 0, players = listOf(Players(42, emptyList()))))
        val embed = MatchMessageGenerator.buildRecentMatchEmbed(match(), "Player", details, heroes) { _, _ -> null }
        assertTrue(embed.title!!.contains("Victory"))
        assertTrue(embed.description!!.contains("30:05"))
        assertEquals(4, embed.fields.size)
        assertTrue(embed.fields.any { it.value.contains("54,321") })
        assertTrue(embed.fields.any { it.value.contains("/match 123456") })
        assertFalse(embed.fields.any { it.value.contains("match_id:") })
        assertFalse(embed.fields.any { it.name.contains("Damage") })
    }

    @Test
    fun `notification uses final stats snapshot and handles deathless games`() {
        val details = MatchDTO(MatchInfoDTO(averageBadgeTeam0 = 0, averageBadgeTeam1 = 0, players = listOf(Players(42, listOf(
            Stats(12000, 2000, 3000, 4000, timeStampSeconds = 1800),
            Stats(100, 200, 300, 400, timeStampSeconds = 60)
        )))))
        val embed = MatchMessageGenerator.buildRecentMatchEmbed(match().copy(deaths = 0), "Player", details, heroes) { _, _ -> null }
        assertEquals(6, embed.fields.size)
        assertTrue(embed.fields.any { it.value.contains("12,000") })
        assertTrue(embed.fields.any { it.value.contains("Deathless") })
    }

    @Test
    fun `unavailable impact values are distinct from measured zero`() {
        val details = MatchDTO(MatchInfoDTO(players = listOf(Players(42, listOf(
            Stats(playerDamage = 0)
        )))))
        val embed = MatchMessageGenerator.buildRecentMatchEmbed(match(), "Player", details, heroes) { _, _ -> null }
        val damage = embed.fields.first { it.name.contains("Damage") }.value
        assertTrue(damage.contains("**0** to players"))
        assertTrue(damage.contains("**—** to objectives"))
    }

    @Test
    fun `unknown results are pending and long names fit Discord title limits`() {
        val embed = MatchMessageGenerator.buildRecentMatchEmbed(
            match().copy(matchResult = -1), "a".repeat(1000), null, heroes
        ) { _, _ -> "" }
        assertTrue(embed.title!!.contains("Result pending"))
        assertTrue(embed.title!!.length <= 256)
    }

    @Test
    fun `last five limits rows and provides an empty state`() {
        val empty = MatchMessageGenerator.buildLastFiveEmbed(emptyList(), null, heroes)
        assertTrue(empty.description!!.contains("No matches"))
        assertTrue(empty.fields.isEmpty())
        val recap = MatchMessageGenerator.buildLastFiveEmbed(List(8) { match() }, "Player", heroes)
        assertEquals(5, recap.fields.size)
        assertTrue(recap.fields.all { it.value.contains("/match 123456") && !it.value.contains("match_id:") })
        assertTrue(recap.description!!.contains("5 W • 0 L"))
        val waiting = MatchMessageGenerator.buildLastFiveEmbed(listOf(match()), "Player", heroes,
            notice = "Waiting for match details: 999.")
        assertTrue(waiting.description!!.startsWith("Waiting for match details: 999."))
    }
}
