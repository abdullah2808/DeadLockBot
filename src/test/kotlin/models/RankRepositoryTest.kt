package models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RankRepositoryTest {

    private fun rank(tier: Int, subrank1: String) = Rank(
        tier = tier,
        name = "Tier$tier",
        images = RankImages(smallSubRankOne = subrank1)
    )

    @Test
    fun `returns the sub-rank image for a valid tier and sub-rank`() {
        val ranks = listOf(rank(0, "zero-1"), rank(1, "one-1"))

        val image = RankRepository.rankImageForBadge(11, ranks) // tier 1, subrank 1

        assertEquals("one-1", image)
    }

    @Test
    fun `rank lookup uses the tier instead of catalog ordering`() {
        val ranks = listOf(rank(2, "two-1"), rank(1, "one-1"))

        assertEquals("one-1", RankRepository.rankImageForBadge(11, ranks))
    }

    @Test
    fun `returns null instead of throwing when the badge's tier is out of range`() {
        val ranks = listOf(rank(0, "zero-1"))

        val image = RankRepository.rankImageForBadge(999, ranks)

        assertNull(image)
    }

    @Test
    fun `returns null for a sub-rank with no matching image field`() {
        val ranks = listOf(rank(0, "zero-1"))

        val image = RankRepository.rankImageForBadge(0, ranks) // subrank 0

        assertNull(image)
    }
}
