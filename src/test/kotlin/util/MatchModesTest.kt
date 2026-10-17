package util

import kotlin.test.*

class MatchModesTest {
    @Test
    fun `ranked, standard and Street Brawl matches are labelled distinctly`() {
        assertEquals("🏅 Ranked", MatchModes.label(1, 4))
        assertEquals("🎮 Standard", MatchModes.label(1, 1))
        // Street Brawl wins over whatever queue the GC reports for it.
        assertEquals("🥊 Street Brawl", MatchModes.label(4, 1))
        assertEquals("🥊 Street Brawl", MatchModes.label(4, 4))
        assertNull(MatchModes.label(0, 0))
    }

    @Test
    fun `only ranked and placement matches can move a badge`() {
        assertTrue(MatchModes.affectsRank(1, 4))
        assertTrue(MatchModes.affectsRank(1, 8))
        assertFalse(MatchModes.affectsRank(1, 1))
        assertFalse(MatchModes.affectsRank(4, 4))
    }
}
