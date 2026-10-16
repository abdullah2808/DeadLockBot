package api.gc

import kotlin.test.*

class GcMetadataBudgetTest {
    private val day = 24 * 60 * 60_000L

    @Test fun `queries spend a rolling daily window with a short minimum gap`() {
        var now = 0L
        val budget = GcMetadataBudget(limit = 3, minIntervalMillis = 20_000, reserved = 0) { now }
        assertEquals(0L, budget.waitMillis(tracked = true))
        budget.consume()
        assertEquals(20_000L, budget.waitMillis(tracked = true))
        now = 20_000; budget.consume()
        now = 40_000; budget.consume()
        now = 60_000
        assertEquals(day - 60_000, budget.waitMillis(tracked = true))
        now = day
        assertEquals(0L, budget.waitMillis(tracked = true))
        assertEquals(1, budget.remaining(tracked = true))
    }

    @Test fun `ad hoc lookups cannot use the share reserved for tracked matches`() {
        var now = 0L
        val budget = GcMetadataBudget(limit = 4, minIntervalMillis = 0, reserved = 2) { now }
        budget.consume(); budget.consume()
        now = 1_000
        assertEquals(day - 1_000, budget.waitMillis(tracked = false))
        assertEquals(0L, budget.waitMillis(tracked = true))
        assertEquals(0, budget.remaining(tracked = false))
        assertEquals(2, budget.remaining(tracked = true))
    }

    @Test fun `history survives restarts and new queries are recorded`() {
        val recorded = mutableListOf<Long>()
        val budget = GcMetadataBudget(limit = 2, minIntervalMillis = 0, reserved = 0,
            load = { listOf(500L, -day) }, record = { recorded += it }) { 1_000L }
        assertEquals(1, budget.remaining(tracked = true))
        budget.consume()
        assertEquals(listOf(1_000L), recorded)
        assertEquals(day - 500, budget.waitMillis(tracked = true))
    }

    @Test fun `persistence failures neither block nor lose the in-memory count`() {
        val budget = GcMetadataBudget(limit = 1, minIntervalMillis = 0, reserved = 0,
            load = { error("database down") }, record = { error("database down") }) { 0L }
        assertEquals(0L, budget.waitMillis(tracked = true))
        budget.consume()
        assertEquals(day, budget.waitMillis(tracked = true))
    }

    @Test fun `repeated rate limits pause longer and success resets the escalation`() {
        var now = 0L
        val budget = GcMetadataBudget(minIntervalMillis = 0) { now }
        assertEquals(60 * 60_000L, budget.rateLimited())
        assertEquals(6 * 60 * 60_000L, budget.rateLimited())
        assertEquals(day, budget.rateLimited())
        now = 2 * day
        budget.succeeded()
        assertEquals(60 * 60_000L, budget.rateLimited())
        assertEquals(60 * 60_000L, budget.waitMillis(tracked = true))
    }
}
