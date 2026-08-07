package bot

import api.gc.MatchHistoryProvider
import models.MatchHistoryDTO
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GcMatchSourceTest {

    private fun match(id: Long) = MatchHistoryDTO(
        accountId = 0, denies = 0, gameMode = 0, heroId = 0, heroLevel = 0, lastHits = 0,
        matchDurationSeconds = 0, matchId = id, matchMode = 0, matchResult = 0, netWorth = 0,
        objectivesMaskTeam0 = 0, objectivesMaskTeam1 = 0, assists = 0, deaths = 0, kills = 0,
        playerTeam = 0, startTime = 0,
    )

    /** Fake provider: returns whatever match list is currently set per account, newest first. */
    private class FakeProvider(private val matchFor: (Long) -> MatchHistoryDTO) : MatchHistoryProvider {
        val byAccount = mutableMapOf<String, List<Long>>()
        var failFor: String? = null

        override suspend fun recentMatches(accountId: String): List<MatchHistoryDTO> {
            if (accountId == failFor) throw RuntimeException("boom")
            return (byAccount[accountId] ?: emptyList()).map(matchFor)
        }
    }

    private fun provider() = FakeProvider(::match)

    @Test
    fun `emits the newest match on first sight, then nothing until it changes`() = runTest {
        val provider = provider().apply { byAccount["107"] = listOf(200L, 199L) }
        val source = GcMatchSource(provider)

        val first = source.pollFinished(setOf("107"))
        assertEquals(1, first.size)
        assertEquals("107", first[0].accountId)
        assertEquals(200L, first[0].matchId)
        assertEquals(200L, first[0].match?.matchId)   // GC summary carried through

        assertTrue(source.pollFinished(setOf("107")).isEmpty())

        provider.byAccount["107"] = listOf(201L, 200L, 199L)
        val next = source.pollFinished(setOf("107"))
        assertEquals(listOf(201L), next.map { it.matchId })
    }

    @Test
    fun `accounts with no history are skipped`() = runTest {
        val provider = provider().apply { byAccount["107"] = emptyList() }
        assertTrue(GcMatchSource(provider).pollFinished(setOf("107")).isEmpty())
    }

    @Test
    fun `a failing lookup does not affect other accounts`() = runTest {
        val provider = provider().apply {
            failFor = "107"
            byAccount["208"] = listOf(500L)
        }
        val finished = GcMatchSource(provider).pollFinished(setOf("107", "208"))
        assertEquals(listOf("208"), finished.map { it.accountId })
        assertEquals(listOf(500L), finished.map { it.matchId })
    }

    @Test
    fun `state is forgotten for untracked accounts so a re-add re-emits`() = runTest {
        val provider = provider().apply { byAccount["107"] = listOf(200L) }
        val source = GcMatchSource(provider)

        assertEquals(listOf(200L), source.pollFinished(setOf("107")).map { it.matchId })
        assertTrue(source.pollFinished(emptySet()).isEmpty())
        assertEquals(listOf(200L), source.pollFinished(setOf("107")).map { it.matchId })
    }
}
