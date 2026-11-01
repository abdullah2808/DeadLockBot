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
        assertFailsWith<ChannelUnavailableException> {
            deliverPendingMatch<String>(match, pending, { throw ChannelUnavailableException(ChannelProblem.MISSING) }, { fail("send") }, { fail("persist") })
        }
        assertTrue(match in pending)
    }

    @Test
    fun `channel problem backs off while keeping the match pending`() {
        val pending = mutableMapOf(match to 0)
        var now = 0L
        val retries = MetadataRetrySchedule { now }
        recordFailedDelivery(match, pending, retries, ChannelUnavailableException(ChannelProblem.NO_ACCESS))
        assertEquals(1, pending[match])
        assertFalse(retries.ready(match))
        now = 90_000L
        assertTrue(retries.ready(match))
    }

    @Test
    fun `other delivery failures retry on the next wake`() {
        val pending = mutableMapOf(match to 0)
        val retries = MetadataRetrySchedule { 0L }
        recordFailedDelivery(match, pending, retries, IllegalStateException("db unavailable"))
        assertEquals(1, pending[match])
        assertTrue(retries.ready(match))
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
