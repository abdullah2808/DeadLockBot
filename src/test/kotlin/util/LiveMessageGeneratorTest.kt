package util

import api.gc.ProfileCard
import api.gc.ProfileHero
import bot.ActiveBoard
import bot.ChannelPlayer
import bot.ListedMatch
import bot.SeatedPlayer
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

    private val listedMatch = ListedMatch(
        ActiveMatchDTO(matchId = 55, startTime = 1_000, netWorthTeam0 = 20_000, netWorthTeam1 = 26_500, matchMode = 4),
        listOf(
            SeatedPlayer(ChannelPlayer(1, "Alice"), ActiveMatchPlayer(1, 0, 7)),
            SeatedPlayer(ChannelPlayer(2, "Bob"), ActiveMatchPlayer(2, 1, 9, abandoned = true)),
        ),
    )

    @Test
    fun `active embed groups a listed match with hero, team, elapsed time, mode and souls`() {
        val embed = LiveMessageGenerator.activeEmbed(ActiveBoard(listOf(listedMatch), emptyList(), emptyList()), true, true, 1_725, heroName)
        val description = embed.description!!
        assertContains(description, "🏅 Ranked · ⏱ 12:05 · Souls: Amber 20,000, Sapphire 26,500 · Match `55`")
        assertContains(description, "• **Alice**: Hero7 for Amber")
        assertContains(description, "• **Bob**: Hero9 for Sapphire (left)")
        assertContains(embed.footer!!.text, "Valve's Watch list")
        assertFalse(description.contains("Not seen"))
    }

    @Test
    fun `active embed never turns presence or absence into a claim it can't support`() {
        val board = ActiveBoard(emptyList(), listOf(ChannelPlayer(3, "Carol", true)),
            listOf(ChannelPlayer(4, "Dan", false), ChannelPlayer(5, "Erin", null)))
        val embed = LiveMessageGenerator.activeEmbed(board, true, true, 1_725, heroName)
        val description = embed.description!!
        assertContains(description, "doesn't prove they're in a match")
        assertContains(description, "• **Dan**: Steam doesn't show them in Deadlock")
        assertContains(description, "• **Erin**: the bot can't see their Steam status")
        assertContains(description, "They may still be playing")
        assertFalse(description.contains("offline", ignoreCase = true))
        assertContains(embed.footer!!.text, "/steambot")
    }

    @Test
    fun `active embed says when the live list is unavailable or came from the fallback`() {
        val board = ActiveBoard(emptyList(), emptyList(), listOf(ChannelPlayer(5, "Erin", null)))
        val unavailable = LiveMessageGenerator.activeEmbed(board, null, false, 1_725, heroName)
        assertContains(unavailable.description!!, "live match list is unavailable")
        assertEquals("Steam status only.", unavailable.footer!!.text)
        assertContains(LiveMessageGenerator.activeEmbed(board, false, false, 1_725, heroName).footer!!.text, "deadlock-api's copy")
    }

    @Test
    fun `active embed escapes names and stays within Discord's limit for very large channels`() {
        val board = ActiveBoard(emptyList(), emptyList(), (1L..300L).map { ChannelPlayer(it, "Player_*$it*" + "x".repeat(40), null) })
        val description = LiveMessageGenerator.activeEmbed(board, true, true, 1_725, heroName).description!!
        assertContains(description, "Player\\_\\*1\\*")
        assertTrue(description.length <= 4096)
        assertContains(description, "more lines.")
    }

    @Test
    fun `match alert groups players and states the coverage limit without claiming the match just started`() {
        val embed = LiveMessageGenerator.matchAlertEmbed(listedMatch, heroName)
        assertEquals("🎮 Alice and Bob are in a match", embed.title)
        val description = embed.description!!
        assertContains(description, "• **Alice**: Hero7 for Amber")
        assertContains(description, "🏅 Ranked · Started <t:1000:R> · Match `55`")
        assertContains(embed.footer!!.text, "other matches never alert")
        assertFalse(description.contains("@"))
    }

    @Test
    fun `match alert titles stay short, and use the reported duration without a start time`() {
        val seat = { id: Long -> SeatedPlayer(ChannelPlayer(id, "P$id" + "y".repeat(100)), ActiveMatchPlayer(id, 0, 7)) }
        val many = LiveMessageGenerator.matchAlertEmbed(ListedMatch(ActiveMatchDTO(matchId = 1, durationS = 65), (1L..5L).map(seat)), heroName)
        assertContains(many.title!!, "and 3 others are in a match")
        assertTrue(many.title!!.length <= 256)
        assertContains(many.description!!, "⏱ 1:05 when posted")
        assertEquals("🎮 ${"P1" + "y".repeat(30)} is in a match",
            LiveMessageGenerator.matchAlertEmbed(ListedMatch(ActiveMatchDTO(matchId = 1), listOf(seat(1))), heroName).title)
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
