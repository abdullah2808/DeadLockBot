package bot

import api.gc.MatchHistoryProvider
import api.gc.MatchHistorySnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import models.MatchDTO
import models.MatchInfoDTO
import models.Players
import models.toMatchHistory
import kotlin.test.*

class RecentMatchLookupTest {
    private fun metadata(id: Long) = MatchDTO(MatchInfoDTO(
        matchId = id, winningTeam = 1, players = listOf(Players(accountId = 107, team = 1, heroId = 88)),
    ))
    private fun gc(vararg ids: Long) = object : MatchHistoryProvider {
        override suspend fun recentMatchIds(accountId: String) = ids.toList()
    }

    @Test
    fun `recent match uses the GC id instead of stale player history`() = runTest {
        val lookup = RecentMatchLookup(gc(115696167), { error("Stale history must not be queried") }, ::metadata)

        val result = lookup.lookup("107", 1)

        assertEquals(115696167L, result.matches.single().matchId)
    }

    @Test
    fun `unavailable newest metadata never substitutes an older match`() = runTest {
        val lookup = RecentMatchLookup(gc(115696167), { listOf(metadata(99).toMatchHistory("107")!!) }, { null })

        val result = lookup.lookup("107", 1)

        assertTrue(result.matches.isEmpty())
        assertEquals(115696167L, result.latestId)
        assertEquals(listOf(115696167L), result.unavailableIds)
    }

    @Test
    fun `last five reports missing details while keeping GC order`() = runTest {
        val lookup = RecentMatchLookup(gc(103, 102, 101), { error("No history fallback") }, {
            if (it == 103L) null else metadata(it)
        })

        val result = lookup.lookup("107", 5)

        assertEquals(listOf(102L, 101L), result.matches.map { it.matchId })
        assertEquals(listOf(103L), result.unavailableIds)
    }

    @Test
    fun `history fallback is available when Steam is not configured`() = runTest {
        val lookup = RecentMatchLookup(null, { listOf(metadata(99).toMatchHistory("107")!!) }, { error("No metadata needed") })
        assertEquals(99L, lookup.lookup("107", 1).matches.single().matchId)
    }

    @Test
    fun `complete GC summaries avoid the metadata API entirely`() = runTest {
        val provider = object : MatchHistoryProvider {
            override suspend fun recentMatchIds(accountId: String) = listOf(115696167L)
            override suspend fun recentHistory(accountId: String) = MatchHistorySnapshot(
                listOf(115696167L), listOf(metadata(115696167).toMatchHistory("107")!!),
            )
        }
        val lookup = RecentMatchLookup(provider, { error("No history request") }, { error("No metadata request") })
        assertEquals(115696167L, lookup.lookup("107", 1).matches.single().matchId)
    }

    @Test
    fun `lookup propagates cancellation`() = runTest {
        val lookup = RecentMatchLookup(gc(99), { emptyList() }, { throw CancellationException("cancelled") })
        assertFailsWith<CancellationException> { lookup.lookup("107", 1) }
    }
}
