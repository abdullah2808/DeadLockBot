package bot

import api.gc.MatchHistoryProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GcMatchSourceTest {

    /** Fake provider: returns whatever match-id list is currently set per account. */
    private class FakeProvider : MatchHistoryProvider {
        val byAccount = mutableMapOf<String, List<Long>>()
        var failFor: String? = null

        override suspend fun recentMatchIds(accountId: String): List<Long> {
            if (accountId == failFor) throw RuntimeException("boom")
            return byAccount[accountId] ?: emptyList()
        }
    }

    @Test
    fun `emits the newest match on first sight, then nothing until it changes`() = runTest {
        val provider = FakeProvider().apply { byAccount["107"] = listOf(200L, 199L) }
        val source = GcMatchSource(provider)

        assertEquals(listOf(FinishedMatch("107", 200L)), source.pollFinished(setOf("107")))
        assertTrue(source.pollFinished(setOf("107")).isEmpty())

        // A newer match appears at the head of the list.
        provider.byAccount["107"] = listOf(201L, 200L, 199L)
        assertEquals(listOf(FinishedMatch("107", 201L)), source.pollFinished(setOf("107")))
    }

    @Test
    fun `accounts with no history are skipped`() = runTest {
        val provider = FakeProvider().apply { byAccount["107"] = emptyList() }
        val source = GcMatchSource(provider)

        assertTrue(source.pollFinished(setOf("107")).isEmpty())
    }

    @Test
    fun `a failing lookup does not affect other accounts`() = runTest {
        val provider = FakeProvider().apply {
            failFor = "107"
            byAccount["208"] = listOf(500L)
        }
        val source = GcMatchSource(provider)

        assertEquals(listOf(FinishedMatch("208", 500L)), source.pollFinished(setOf("107", "208")))
    }

    @Test
    fun `state is forgotten for untracked accounts so a re-add re-emits`() = runTest {
        val provider = FakeProvider().apply { byAccount["107"] = listOf(200L) }
        val source = GcMatchSource(provider)

        assertEquals(listOf(FinishedMatch("107", 200L)), source.pollFinished(setOf("107")))
        // Untracked this cycle → its remembered state is dropped.
        assertTrue(source.pollFinished(emptySet()).isEmpty())
        // Re-added → same newest id is emitted again.
        assertEquals(listOf(FinishedMatch("107", 200L)), source.pollFinished(setOf("107")))
    }
}
