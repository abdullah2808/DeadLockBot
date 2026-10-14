package bot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MatchCommandInputTest {
    @Test fun `accept numeric IDs and legacy copied option labels`() {
        assertEquals(116398473L, parseMatchId("116398473"))
        assertEquals(116398473L, parseMatchId(" match_id:116398473 "))
        assertEquals(116398473L, parseMatchId("match_id: 116398473"))
    }
    @Test fun `reject invalid or nonpositive IDs`() {
        listOf("", "match_id:", "0", "-1", "not-a-match", "999999999999999999999").forEach { assertNull(parseMatchId(it)) }
    }
}
