package bot

import api.DeadlockClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import models.ActiveMatchDTO
import models.ActiveMatchPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatchSourceTest {

    // reconcile() never touches the network, but ActiveFeedMatchSource needs a
    // client to construct; a no-op mock keeps the test fully offline.
    private fun newSource(): ActiveFeedMatchSource =
        ActiveFeedMatchSource(DeadlockClient(HttpClient(MockEngine { respond("[]", HttpStatusCode.OK) })))

    private fun match(matchId: Long, vararg accountIds: Long) =
        ActiveMatchDTO(
            matchId = matchId,
            players = accountIds.map { ActiveMatchPlayer(accountId = it) }
        )

    @Test
    fun `first sighting of a live match reports nothing`() {
        val source = newSource()
        val finished = source.reconcile(listOf(match(1, 107L)), setOf("107"))
        assertTrue(finished.isEmpty())
    }

    @Test
    fun `account leaving the feed reports the finished match`() {
        val source = newSource()
        source.reconcile(listOf(match(1, 107L)), setOf("107"))          // now live

        val finished = source.reconcile(emptyList(), setOf("107"))       // left the feed

        assertEquals(listOf(FinishedMatch("107", 1)), finished)
    }

    @Test
    fun `account still in the same match reports nothing`() {
        val source = newSource()
        source.reconcile(listOf(match(1, 107L)), setOf("107"))

        val finished = source.reconcile(listOf(match(1, 107L)), setOf("107"))

        assertTrue(finished.isEmpty())
    }

    @Test
    fun `account moving to a new match reports the previous one`() {
        val source = newSource()
        source.reconcile(listOf(match(1, 107L)), setOf("107"))

        val finished = source.reconcile(listOf(match(2, 107L)), setOf("107"))

        assertEquals(listOf(FinishedMatch("107", 1)), finished)
    }

    @Test
    fun `a finished match is reported only once`() {
        val source = newSource()
        source.reconcile(listOf(match(1, 107L)), setOf("107"))
        val first = source.reconcile(emptyList(), setOf("107"))
        val second = source.reconcile(emptyList(), setOf("107"))

        assertEquals(listOf(FinishedMatch("107", 1)), first)
        assertTrue(second.isEmpty())
    }

    @Test
    fun `untracked accounts are ignored`() {
        val source = newSource()
        source.reconcile(listOf(match(1, 999L)), setOf("107"))

        val finished = source.reconcile(emptyList(), setOf("107"))

        assertTrue(finished.isEmpty())
    }

    @Test
    fun `two tracked players in the same match both report on finish`() {
        val source = newSource()
        source.reconcile(listOf(match(1, 107L, 208L)), setOf("107", "208"))

        val finished = source.reconcile(emptyList(), setOf("107", "208")).toSet()

        assertEquals(setOf(FinishedMatch("107", 1), FinishedMatch("208", 1)), finished)
    }
}
