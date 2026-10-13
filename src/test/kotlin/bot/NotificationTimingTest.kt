package bot

import kotlin.test.*

class NotificationTimingTest {
    private val match = FinishedMatch("107", 200)

    @Test fun `discovery waits one minute and wakes before next history poll`() {
        var now = 0L
        val timing = NotificationTiming(clock = { now })
        assertTrue(timing.pollDue())
        timing.discovered(match)
        timing.pollFinished()
        assertEquals(60_000L, timing.nextWakeDelayMillis())
        now = 59_999
        assertFalse(timing.ready(match))
        assertEquals(1L, timing.nextWakeDelayMillis())
        now = 60_000
        assertTrue(timing.ready(match))
        assertFalse(timing.pollDue())
        assertEquals(30_000L, timing.nextWakeDelayMillis())
        now = 90_000
        assertTrue(timing.pollDue())
    }

    @Test fun `party members wait together without a minute per account`() {
        var now = 0L
        val timing = NotificationTiming(clock = { now })
        val teammate = FinishedMatch("208", match.matchId)
        timing.discovered(match)
        timing.discovered(teammate)
        timing.pollFinished()
        now = 60_000
        assertTrue(timing.ready(match))
        assertTrue(timing.ready(teammate))
        timing.forget(match)
        assertTrue(timing.ready(teammate))
        assertEquals(30_000L, timing.nextWakeDelayMillis())
    }

    @Test fun `repeated discovery and send retries do not restart grace period`() {
        var now = 0L
        val timing = NotificationTiming(clock = { now })
        timing.discovered(match)
        timing.pollFinished()
        now = 30_000
        timing.discovered(match)
        assertEquals(30_000L, timing.nextWakeDelayMillis())
        now = 60_000
        assertTrue(timing.ready(match))
        now = 90_000
        timing.pollFinished()
        timing.discovered(match)
        assertTrue(timing.ready(match))
        assertEquals(90_000L, timing.nextWakeDelayMillis())
    }

    @Test fun `removing completed or unsubscribed matches removes their wake deadlines`() {
        var now = 0L
        val timing = NotificationTiming(clock = { now })
        timing.discovered(match)
        timing.pollFinished()
        now = 20_000
        timing.forget(match)
        assertFalse(timing.ready(match))
        assertEquals(70_000L, timing.nextWakeDelayMillis())
    }

    @Test fun `new independent match keeps its own deadline while ready failures wait for polling`() {
        var now = 0L
        val timing = NotificationTiming(clock = { now })
        val next = FinishedMatch("208", 201)
        timing.discovered(match)
        timing.pollFinished()
        now = 30_000
        timing.discovered(next)
        now = 60_000
        assertTrue(timing.ready(match))
        assertFalse(timing.ready(next))
        assertEquals(30_000L, timing.nextWakeDelayMillis())
        now = 90_000
        timing.pollFinished()
        assertTrue(timing.ready(next))
        assertEquals(90_000L, timing.nextWakeDelayMillis())
    }

    @Test fun `time spent polling does not shorten grace and failed cycles can defer polling`() {
        var now = 0L
        val timing = NotificationTiming(clock = { now })
        now = 20_000
        timing.discovered(match)
        timing.pollFinished()
        assertEquals(60_000L, timing.nextWakeDelayMillis())
        now = 79_999
        assertFalse(timing.ready(match))
        now = 80_000
        assertTrue(timing.ready(match))
        now = 150_000
        assertTrue(timing.pollDue())
        assertEquals(1L, timing.nextWakeDelayMillis())
        timing.pollFinished()
        assertEquals(90_000L, timing.nextWakeDelayMillis())
    }
}
