package bot

import kotlin.test.*

class MetadataRetryScheduleTest {
    @Test
    fun `metadata remains pending after more than five failures with bounded backoff`() {
        var now = 0L
        val retries = MetadataRetrySchedule { now }
        val match = FinishedMatch("107", 115696167)
        val pending = mutableMapOf(match to 0)
        repeat(10) {
            assertTrue(retries.ready(match))
            val wait = deferPendingMetadata(match, pending, retries)
            assertTrue(wait in 90_000L..900_000L)
            assertFalse(retries.ready(match))
            now += wait
        }
        assertEquals(10, pending[match])
        assertTrue(retries.ready(match))
    }
}
