package api.gc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.*

class GcRequestGateTest {
    @Test fun `metadata pacing spans accounts and leaves history available`() = runTest {
        var now = 0L
        var sends = 0
        val gate = GcRequestGate(mapOf(9168 to 1_800_000L)) { now }
        suspend fun query(type: Int, result: Int = 1) = gate.request(type, { it: Int -> it }) { sending -> sending(); sends++; result }
        assertEquals(1, query(9168))
        assertEquals(1_800_000L, assertFailsWith<GcRequestDeferredException> { query(9168) }.retryAfterMillis)
        assertEquals(1, query(9113))
        assertEquals(2, sends)
        now = 1_800_000
        assertEquals(2, query(9168, 2))
        assertFailsWith<GcRequestDeferredException> { query(9168) }
        assertEquals(3, sends)
    }
    @Test fun `a query that never left the process does not spend the metadata interval`() = runTest {
        val gate = GcRequestGate(mapOf(9168 to 1_800_000L)) { 0L }
        assertFailsWith<IOException> { gate.request<Int>(9168, { it }) { throw IOException("GC session not ready") } }
        assertFailsWith<CancellationException> { gate.request<Int>(9168, { it }) { throw CancellationException() } }
        assertEquals(1, gate.request(9168, { it: Int -> it }) { sending -> sending(); 1 })
        assertFailsWith<GcRequestDeferredException> { gate.request(9168, { it: Int -> it }) { 1 } }
    }
    @Test fun `cooldown blocks both endpoints without sending and permits probe at expiry`() = runTest {
        var now = 0L
        var sends = 0
        val gate = GcRequestGate { now }
        suspend fun query(operation: Int, response: Int) = gate.request(operation, { it: Int -> it }) { sends++; response }
        assertEquals(60_000L, assertFailsWith<GcRateLimitedException> { query(1, 5) }.retryAfterMillis)
        now = 59_999
        assertEquals(1L, assertFailsWith<GcRateLimitedException> { query(2, 1) }.retryAfterMillis)
        assertEquals(1, sends)
        now = 60_000
        assertEquals(1, query(1, 1))
        assertEquals(2, sends)
        assertEquals(60_000L, assertFailsWith<GcRateLimitedException> { query(1, 5) }.retryAfterMillis)
    }

    @Test fun `repeated throttling doubles to cap and other endpoint success does not reset it`() = runTest {
        var now = 0L
        val gate = GcRequestGate { now }
        for (expected in listOf(60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L)) {
            val limited = assertFailsWith<GcRateLimitedException> { gate.request(1, { it: Int -> it }) { 5 } }
            assertEquals(expected, limited.retryAfterMillis)
            now += expected
            assertEquals(1, gate.request(2, { it: Int -> it }) { 1 })
        }
    }

    @Test fun `queued request observes cooldown before sending`() = runTest {
        var sends = 0
        val gate = GcRequestGate { 0L }
        val first = async {
            assertFailsWith<GcRateLimitedException> {
                gate.request(1, { it: Int -> it }) { sends++; delay(100); 5 }
            }
        }
        val second = async {
            assertFailsWith<GcRateLimitedException> {
                gate.request(2, { it: Int -> it }) { sends++; 1 }
            }
        }
        first.await()
        second.await()
        assertEquals(1, sends)
    }

    @Test fun `permission errors transport failures and cancellation do not trigger cooldown`() = runTest {
        val gate = GcRequestGate { 0L }
        assertEquals(2, gate.request(1, { it: Int -> it }) { 2 })
        assertFailsWith<IOException> { gate.request<Int>(1, { it }) { throw IOException("offline") } }
        assertFailsWith<CancellationException> { gate.request<Int>(1, { it }) { throw CancellationException() } }
        assertEquals(1, gate.request(1, { it: Int -> it }) { 1 })
    }
}
