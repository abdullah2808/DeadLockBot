package util

import models.ItemInfo
import models.MatchItem
import models.Players
import kotlin.test.*

class AbilityProgressionTest {
    private val assets = ScoreboardAssets(catalog = mapOf(20L to ItemInfo(20, "Fixation", "ability"),
        21L to ItemInfo(21, "Sleep Dagger", "ability")))

    @Test fun `unlock and three upgrades show tiers and the actual AP cost in chronological order`() {
        val player = Players(42, items = listOf(MatchItem(20, 300, upgradeId = 2006215127),
            MatchItem(20, 55, upgradeId = 0), MatchItem(20, 100, upgradeId = 334138523),
            MatchItem(20, 200, upgradeId = 75043054)))
        val events = InteractiveMatchReport.playerEvents(player, listOf(player), assets)
        assertEquals(listOf(55, 100, 200, 300), events.map { it.time })
        assertEquals(listOf("Unlocked Fixation · Tier 0", "Upgraded Fixation · Tier 1",
            "Upgraded Fixation · Tier 2", "Upgraded Fixation · Tier 3"), events.map { it.label })
        assertEquals(listOf("1 AP", "2 AP", "5 AP"), events.drop(1).map { it.detail.substringBefore(" · ") })
    }

    @Test fun `abilities advance independently and duplicate upgrade IDs do not spend another tier`() {
        val progression = AbilityProgression()
        assertEquals(0, progression.tier(MatchItem(20, upgradeId = 0)))
        assertEquals(1, progression.tier(MatchItem(20, upgradeId = 100)))
        assertEquals(0, progression.tier(MatchItem(21, upgradeId = 0)))
        assertEquals(1, progression.tier(MatchItem(20, upgradeId = 100)))
        assertEquals(2, progression.tier(MatchItem(20, upgradeId = 200)))
        assertEquals(1, progression.tier(MatchItem(21, upgradeId = 300)))
    }

    @Test fun `missing unlocks missing upgrade records and extra tiers remain unknown`() {
        val progression = AbilityProgression()
        assertNull(progression.tier(MatchItem(20, upgradeId = 100)))
        assertEquals(0, progression.tier(MatchItem(20, upgradeId = 0)))
        assertNull(progression.tier(MatchItem(20)))
        assertNull(progression.tier(MatchItem(20, upgradeId = 200)))
        assertEquals(0, progression.tier(MatchItem(20, upgradeId = 0)))
        for (tier in 1..3) assertEquals(tier, progression.tier(MatchItem(20, upgradeId = tier.toLong())))
        assertNull(progression.tier(MatchItem(20, upgradeId = 4)))
    }
}
