package api.gc

import kotlinx.coroutines.test.runTest
import models.MatchDTO
import models.MatchInfoDTO
import models.Players
import models.toMatchHistory
import kotlin.test.*

class CachedMatchHistoryProviderTest {
    @Test
    fun `scheduler and commands reuse a recent GC snapshot and persistent summary`() = runTest {
        var queries = 0
        var now = 0L
        val match = MatchDTO(MatchInfoDTO(matchId = 99, players = listOf(Players(accountId = 107)))).toMatchHistory("107")!!
        var persisted = emptyList<models.MatchHistoryDTO>()
        val delegate = object : MatchHistoryProvider {
            override suspend fun recentMatchIds(accountId: String): List<Long> = error("Use the complete history")
            override suspend fun recentHistory(accountId: String): MatchHistorySnapshot {
                queries++
                return MatchHistorySnapshot(listOf(99), listOf(match))
            }
        }
        val cache = CachedMatchHistoryProvider(delegate, save = { _, rows -> persisted = rows },
            find = { _, id -> persisted.firstOrNull { it.matchId == id } }, clock = { now })
        assertEquals(listOf(99L), cache.recentMatchIds("107"))
        assertEquals(match, cache.recentHistory("107").summaries.single())
        assertEquals(match, cache.cachedMatch("107", 99))
        assertEquals(1, queries)
        now = 30_000
        cache.recentHistory("107")
        assertEquals(2, queries)
    }
}
