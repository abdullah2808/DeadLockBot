package bot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import models.*
import kotlin.test.*

class StatCompareTest {
    private fun summary(id: Long, account: Int = 1, team: Int = 0, result: Int = 0, hero: Int = 1, duration: Int = 600,
                        kills: Int = 8, deaths: Int = 2, assists: Int = 4, souls: Int = 6000, gameMode: Int = 1, matchMode: Int = 1) =
        MatchDTO(MatchInfoDTO(matchId = id, startTime = id.toInt(), durationSeconds = duration, winningTeam = result,
            gameMode = gameMode, matchMode = matchMode,
            players = listOf(Players(account, team = team, heroId = hero, kills = kills, deaths = deaths, assists = assists,
                netWorth = souls, lastHits = 100, denies = 10)))).toMatchHistory(account.toString())!!

    private fun sample(id: Long, account: Int = 1, duration: Int = 600, damage: Int? = null, deaths: Int = 2): TrendSample {
        val row = summary(id, account, duration = duration, deaths = deaths)
        val metadata = damage?.let { MatchDTO(MatchInfoDTO(matchId = id, players = listOf(Players(account, stats = listOf(Stats(playerDamage = it)))))) }
        return TrendSample(row, metadata)
    }

    @Test fun `rates are weighted by minutes and missing metadata never lowers damage`() {
        val stats = compareStats(listOf(sample(1, duration = 600, damage = 10000), sample(2, duration = 1200, damage = 20000), sample(3)))!!
        assertEquals(3, stats.games)
        assertEquals(1000.0, stats.damagePerMin)
        assertEquals(2, stats.damageCoverage)
        assertEquals(6000 * 3 / 40.0, stats.soulsPerMin)
        assertNull(stats.healingPerMin)
    }

    @Test fun `deathless samples still produce a finite kda`() {
        assertEquals(12.0, compareStats(listOf(sample(1, deaths = 0)))!!.kda)
        assertNull(compareStats(emptyList()))
    }

    @Test fun `completed samples drop duplicates and unfinished matches and sort newest first`() {
        val unknown = TrendSample(summary(9, result = -1))
        val ids = completedSamples(listOf(sample(1), sample(3), sample(3), sample(2), unknown), CompareMode.ALL).map { it.summary.matchId }
        assertEquals(listOf(3L, 2L, 1L), ids)
    }

    @Test fun `mode filter separates ranked standard and street brawl`() {
        val standard = summary(1)
        val ranked = summary(2, matchMode = 4)
        val placement = summary(3, matchMode = 8)
        val brawl = summary(4, gameMode = 4, matchMode = 1)
        val rows = listOf(standard, ranked, placement, brawl).map { TrendSample(it) }
        fun ids(mode: CompareMode) = completedSamples(rows, mode).map { it.summary.matchId }.sorted()
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(CompareMode.ALL))
        assertEquals(listOf(2L, 3L), ids(CompareMode.RANKED))
        assertEquals(listOf(1L), ids(CompareMode.STANDARD))
        assertEquals(listOf(4L), ids(CompareMode.STREET_BRAWL))
        assertEquals(CompareMode.ALL, CompareMode.fromOption(null))
        assertEquals(CompareMode.RANKED, CompareMode.fromOption("ranked"))
    }

    @Test fun `shared matches split teammates from opponents and skip inconsistent results`() {
        val left = listOf(summary(1), summary(2, result = 1), summary(3), summary(4, result = 1), summary(5), summary(6))
        val right = listOf(summary(1, 2), summary(2, 2, result = 1), summary(3, 2, team = 1), summary(4, 2, team = 1, result = 1),
            summary(5, 2, result = 1))
        assertEquals(HeadToHead(togetherWins = 1, togetherLosses = 1, leftWins = 1, rightWins = 1), headToHead(left, right))
        assertEquals(0, headToHead(left, emptyList()).shared)
    }

    @Test fun `table stars the better value and treats fewer deaths as better`() {
        val left = compareStats(listOf(sample(1, deaths = 1)))!!
        val right = compareStats(listOf(sample(2, account = 2, deaths = 5)))!!
        val table = compareTable("Arrow", "bay", left, right)
        val deaths = table.lines().single { it.startsWith("Deaths") }
        assertContains(deaths, "1.0*")
        assertFalse(deaths.endsWith("*"))
        assertTrue(table.lines().single { it.startsWith("KDA") }.contains("12.00*"))
    }

    @Test fun `values that print the same are not starred and missing stats show a dash`() {
        val left = compareStats(listOf(sample(1, damage = 10000)))!!
        val right = compareStats(listOf(sample(2, account = 2)))!!
        val table = compareTable("Arrow", "bay", left, right)
        assertFalse(table.lines().single { it.startsWith("Kills") }.contains("*"))
        val damage = table.lines().single { it.startsWith("Damage") }
        assertContains(damage, "—")
        assertFalse(damage.contains("*"))
    }

    @Test fun `table rows stay aligned and names cannot break out of the code block`() {
        val stats = compareStats(listOf(sample(1, damage = 1000)))!!
        val lines = compareTable("a`very`long`name\nindeed", "bay", stats, stats).lines()
        assertEquals(1, lines.map { it.length }.distinct().size)
        assertFalse(lines.any { "`" in it })
        assertEquals(12, lines.size)
    }

    @Test fun `embed explains partial data and fits discord limits`() {
        val left = compareStats(listOf(sample(1, damage = 1000), sample(2)))!!
        val right = compareStats(listOf(sample(3, account = 2)))!!
        val embed = compareEmbed("x".repeat(80), "*bay*", left, right, HeadToHead(1, 0, 2, 3), CompareMode.RANKED, 20) { "Hero $it" }
        assertTrue(embed.title!!.length <= 256)
        assertContains(embed.title!!, "\\*bay\\*")
        assertContains(embed.description!!, "Ranked")
        assertContains(embed.footer!!.text, "fewer matches")
        assertContains(embed.footer!!.text, "damage from 1/2 and 0/1")
        assertContains(embed.fields.last().value, "1W/0L")
        assertContains(embed.fields.last().value, "2 – 3")
    }

    @Test fun `tracked accounts use stored rows without any lookup`() = runTest {
        val rows = listOf(sample(1))
        assertEquals(rows, loadCompareSamples(true, { rows }, { fail("no live lookup for a tracked account") }))
    }

    @Test fun `tracked accounts without rows and untracked accounts ask live and keep stored metadata`() = runTest {
        val stored = listOf(sample(1, damage = 1000))
        val result = loadCompareSamples(false, { stored }, { listOf(summary(1), summary(2)) })
        assertEquals(listOf(1L, 2L), result.map { it.summary.matchId })
        assertNotNull(result[0].metadata)
        assertNull(result[1].metadata)
        assertEquals(1, loadCompareSamples(true, { emptyList() }, { listOf(summary(5)) }).size)
    }

    @Test fun `failed lookups fall back to stored rows but cancellation propagates`() = runTest {
        val stored = listOf(sample(1))
        assertEquals(stored, loadCompareSamples(false, { stored }, { error("GC down") }))
        assertTrue(loadCompareSamples(false, { emptyList() }, { error("GC down") }).isEmpty())
        assertFailsWith<CancellationException> { loadCompareSamples(false, { stored }, { throw CancellationException("stop") }) }
    }

    @Test fun `compare command is registered with its toggle last`() {
        val options = dev.kord.rest.builder.interaction.GlobalMultiApplicationCommandBuilder().apply { botCommands() }.build()
            .single { it.name == "compare" }.options.value.orEmpty()
        assertEquals(listOf("player", "account_id", "against", "mode", "matches", "public"), options.map { it.name })
    }
}
