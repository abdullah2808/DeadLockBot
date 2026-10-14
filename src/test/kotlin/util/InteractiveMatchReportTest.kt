package util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import models.HeroInfo
import models.ItemInfo
import models.MatchDTO
import models.MatchInfoDTO
import models.MatchItem
import models.Players
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InteractiveMatchReportTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val catalog = mapOf(
        10L to ItemInfo(10, "Extended Magazine", "upgrade", description = json.parseToJsonElement("""{"desc":"More <b>ammo</b>."}""").jsonObject),
        20L to ItemInfo(20, "Fixation", "ability", className = "ability_stacking_damage",
            description = json.parseToJsonElement("""{"desc":"Deal more damage.","t1_desc":"Extra damage."}""").jsonObject)
    )
    private val assets = ScoreboardAssets(heroes = mapOf(13 to HeroInfo("Haze", null, listOf("ability_stacking_damage"))), catalog = catalog)

    @Test
    fun `report retains purchases sales and ability events in time order`() {
        val player = Players(42, heroId = 13, items = listOf(
            MatchItem(20, 120, upgradeId = 123), MatchItem(10, 30, soldTimeSeconds = 90), MatchItem(20, 150, upgradeId = 456)
        ))
        val html = render(player)
        assertTrue(html.indexOf("Bought Extended Magazine") < html.indexOf("Sold Extended Magazine"))
        assertTrue(html.indexOf("Sold Extended Magazine") < html.indexOf("Ability point · Fixation"))
        assertTrue(html.contains("Recorded ability upgrade · exact tier not inferred"))
        assertTrue(html.contains("data-time=\"150\" data-kind=\"ability\""))
        assertTrue(html.contains("1. Fixation"))
        assertTrue(html.contains("More ammo."))
    }

    @Test
    fun `untrusted names and descriptions cannot inject HTML or scripts`() {
        val hostile = "\"><script>alert(1)</script>"
        val html = InteractiveMatchReport.render(MatchDTO(MatchInfoDTO(players = listOf(Players(42, heroId = 13, name = hostile)))),
            1, assets.copy(heroes = mapOf(13 to HeroInfo(hostile, null)))).toString(Charsets.UTF_8)
        assertFalse(html.contains(hostile))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue(html.contains("default-src 'none'"))
        assertFalse(html.contains("src=\"http"))
    }

    @Test
    fun `older cached data explains missing progression`() {
        val html = render(Players(42, heroId = 13))
        assertTrue(html.contains("Older bot versions did not retain them"))
        assertTrue(html.contains("Unavailable"))
    }

    @Test
    fun `write interactive layout preview`() {
        val match = json.decodeFromString<MatchDTO>(javaClass.getResource("/match-metadata.json")!!.readText())
        val html = InteractiveMatchReport.render(match, match.matchInfo.matchId, assets)
        File("build/interactive-report-preview.html").apply { parentFile.mkdirs(); writeBytes(html) }
        assertTrue(html.toString(Charsets.UTF_8).contains("id=\"time\""))
    }

    private fun render(player: Players) = InteractiveMatchReport.render(MatchDTO(MatchInfoDTO(players = listOf(player), durationSeconds = 180)),
        123, assets).toString(Charsets.UTF_8)
}
