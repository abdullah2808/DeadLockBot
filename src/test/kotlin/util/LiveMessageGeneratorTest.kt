package util

import api.gc.ProfileCard
import api.gc.ProfileHero
import models.ActiveMatchDTO
import models.ActiveMatchPlayer
import models.Rank
import models.RankImages
import models.RankRepository
import kotlin.test.*

class LiveMessageGeneratorTest {
    private val heroName: (Int) -> String = { "Hero$it" }

    @Test
    fun `profile embed shows rank, stats, win rate and featured heroes`() {
        val rank = RankRepository.describeBadge(74, listOf(Rank(7, "Ritualist", RankImages(smallSubRankFour = "https://img/4.png"))))
        val card = ProfileCard(107, 74, listOf(ProfileHero(7, 1200, 34000)), gamesPlayed = 200, wins = 110, kills = 3000)
        val embed = LiveMessageGenerator.profileEmbed("Player", card, rank, heroName)
        assertContains(embed.description!!, "Ritualist 4")
        assertContains(embed.description!!, "**Win rate:** 55.0%")
        assertContains(embed.description!!, "**Kills:** 3,000")
        assertEquals("https://img/4.png", embed.thumbnail?.url)
        assertContains(embed.fields.single().value, "**Hero7** — 1,200 wins · 34,000 kills")
    }

    @Test
    fun `profile embed explains a missing rank`() {
        val embed = LiveMessageGenerator.profileEmbed("Player", ProfileCard(107, null, emptyList()), null, heroName)
        assertContains(embed.description!!, "Unranked or hidden")
        assertTrue(embed.fields.isEmpty())
        assertNull(embed.thumbnail)
    }

    @Test
    fun `live embed shows the player's hero, both rosters and the soul lead`() {
        val match = ActiveMatchDTO(
            matchId = 55, startTime = 1_000, spectators = 3, netWorthTeam0 = 20_000, netWorthTeam1 = 26_500, matchMode = 4,
            players = listOf(
                ActiveMatchPlayer(107, 0, 7), ActiveMatchPlayer(200, 0, 8, abandoned = true), ActiveMatchPlayer(300, 1, 9),
            ),
        )
        val embed = LiveMessageGenerator.liveEmbed("Player", 107, match, mapOf(300L to "Friend"), nowEpochSeconds = 1_725, heroName = heroName)
        assertContains(embed.title!!, "Match 55")
        val description = embed.description!!
        assertContains(description, "Playing **Hero7** for **Amber**")
        assertContains(description, "🏅 Ranked · ⏱ 12:05 · 👁 3 watching")
        assertContains(description, "(Sapphire +6,500)")
        assertEquals(listOf("Amber", "Sapphire"), embed.fields.map { it.name })
        assertContains(embed.fields[0].value, "Hero8 (left)")
        assertContains(embed.fields[1].value, "Hero9 — **Friend**")
    }

    @Test
    fun `elapsed time falls back to the reported duration`() {
        assertEquals(90, LiveMessageGenerator.elapsedSeconds(ActiveMatchDTO(matchId = 1, durationS = 90), 5_000))
        assertNull(LiveMessageGenerator.elapsedSeconds(ActiveMatchDTO(matchId = 1), 5_000))
    }

    @Test
    fun `rank change embeds describe ups, downs and placements`() {
        val ranks = listOf(Rank(5, "Arcanist", RankImages(smallSubRankFour = "https://img/a4.png", smallSubRankFive = "https://img/a5.png")))
        val up = LiveMessageGenerator.rankChangeEmbed("Player", bot.RankChange(54, 55),
            RankRepository.describeBadge(54, ranks), RankRepository.describeBadge(55, ranks))
        assertEquals("📈 Player ranked up!", up.title)
        assertEquals("Arcanist 4 → **Arcanist 5**", up.description)
        assertEquals("https://img/a5.png", up.thumbnail?.url)
        val down = LiveMessageGenerator.rankChangeEmbed("Player", bot.RankChange(55, 54), null, null)
        assertEquals("Badge 55 → **Badge 54**", down.description)
        val placed = LiveMessageGenerator.rankChangeEmbed("Player", bot.RankChange(null, 54), null, RankRepository.describeBadge(54, ranks))
        assertEquals("Now **Arcanist 4**", placed.description)
    }
}
