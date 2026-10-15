package bot

import kotlin.test.*
import models.*
import util.ScoreboardAssets

class MatchExplorerTest {
    private val assets = ScoreboardAssets(catalog = mapOf(1L to ItemInfo(1, "Magazine", "upgrade"), 2L to ItemInfo(2, "Ability", "ability")))
    private fun report(player: Players) = MatchExplorer.Report(MatchDTO(MatchInfoDTO(players = listOf(player))), 123, assets, 0)

    @Test fun `build excludes abilities and sold items`() {
        val r = report(Players(42, items = listOf(MatchItem(1), MatchItem(2), MatchItem(3, soldTimeSeconds = 90))))
        val text = MatchExplorer.text(r, MatchExplorer.View(section = "build"))
        assertTrue(text.contains("Magazine"))
        assertFalse(text.contains("Ability"))
        assertFalse(text.contains("Asset 3"))
    }

    @Test fun `event pagination and filters preserve times`() {
        val r = report(Players(42, items = (1..20).map { MatchItem(1, it * 60) }))
        val page = MatchExplorer.text(r, MatchExplorer.View(section = "events", page = 1))
        assertTrue(page.contains("Page 2/3"))
        assertTrue(page.contains("`9:00`"))
        assertFalse(page.contains("`1:00`"))
        assertTrue(MatchExplorer.text(r, MatchExplorer.View(section = "events", filter = "kill")).contains("No recorded events"))
    }

    @Test fun `missing metadata and oversized descriptions remain safe`() {
        val text = MatchExplorer.text(report(Players(42)), MatchExplorer.View(section = "events"))
        assertTrue(text.contains("Build event data unavailable"))
        assertTrue(text.contains("Combat coverage may be incomplete"))
        assertTrue(MatchExplorer.text(report(Players(42)), MatchExplorer.View()).contains("Player damage: —"))
    }

    @Test fun `navigation payload round trips and component layouts fit Discord limits`() {
        val parsed = MatchExplorer.parse("mx:key:page:0:events:death:2:0".split(":"))
        assertEquals(MatchExplorer.View(section = "events", filter = "death", page = 2), parsed)
        val r = report(Players(42, items = listOf(MatchItem(1))))
        for (section in listOf("stats", "build", "events", "abilities")) {
            val builder = dev.kord.rest.builder.message.create.UserMessageCreateBuilder()
            MatchExplorer.controls(builder, "key", r, MatchExplorer.View(section = section))
            assertTrue(builder.components!!.size <= 5)
            assertTrue(MatchExplorer.text(r, MatchExplorer.View(section = section)).length <= 2000)
        }
    }

    @Test fun `cache retains metadata without image buffers and bounds entries`() {
        val keys = (1..51).map { MatchExplorer.store(report(Players(42)).match, it.toLong(), assets) }
        assertNull(MatchExplorer.get(keys.first()))
        assertEquals(51, MatchExplorer.get(keys.last())?.matchId)
        assertNull(MatchExplorer.get("missing"))
    }
}
