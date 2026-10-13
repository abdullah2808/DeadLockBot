package bot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SchedulerTest {
    private val match = FinishedMatch("107", 200L)

    @Test
    fun `send precedes persistence`() = runTest {
        val pending = mutableMapOf(match to 0)
        val events = mutableListOf<String>()
        deliverPendingMatch(match, pending, { "channel" }, { events.add("send") }, { events.add("persist") })
        assertEquals(listOf("send", "persist"), events)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun `missing channel preserves pending`() = runTest {
        val pending = mutableMapOf(match to 0)
        assertFailsWith<IllegalArgumentException> {
            deliverPendingMatch<String>(match, pending, { null }, { fail("send") }, { fail("persist") })
        }
        assertTrue(match in pending)
    }

    @Test
    fun `failed send remains retryable`() = runTest {
        val pending = mutableMapOf(match to 0)
        var persisted = false
        assertFailsWith<IllegalStateException> {
            deliverPendingMatch(match, pending, { "channel" }, { error("send failed") }, { persisted = true })
        }
        assertFalse(persisted)
        assertTrue(match in pending)
        deliverPendingMatch(match, pending, { "channel" }, {}, { persisted = true })
        assertTrue(persisted)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun `cancellation propagates`() = runTest {
        val pending = mutableMapOf(match to 0)
        assertFailsWith<CancellationException> {
            deliverPendingMatch(match, pending, { "channel" }, { throw CancellationException() }, { fail("persist") })
        }
        assertTrue(match in pending)
    }

    @Test
    fun `persistence failure preserves pending`() = runTest {
        val pending = mutableMapOf(match to 0)
        assertFailsWith<IllegalStateException> {
            deliverPendingMatch(match, pending, { "channel" }, {}, { error("db unavailable") })
        }
        assertTrue(match in pending)
    }
}
