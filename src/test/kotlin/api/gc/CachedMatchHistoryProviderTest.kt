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
            find = { _, id -> persisted.firstOrNull { it.matchId == id } }, clock = { now }, isTracked = { true })
        assertEquals(listOf(99L), cache.recentMatchIds("107"))
        assertEquals(match, cache.recentHistory("107").summaries.single())
        assertEquals(match, cache.cachedMatch("107", 99))
        assertEquals(1, queries)
        now = 30_000
        cache.recentHistory("107")
        assertEquals(2, queries)
    }

    private val summary = MatchDTO(MatchInfoDTO(matchId = 99, players = listOf(Players(accountId = 107)))).toMatchHistory("107")!!
    private fun counting(onQuery: () -> Unit) = object : MatchHistoryProvider {
        override suspend fun recentMatchIds(accountId: String): List<Long> = error("Use the complete history")
        override suspend fun recentHistory(accountId: String): MatchHistorySnapshot {
            onQuery()
            return MatchHistorySnapshot(listOf(99), listOf(summary))
        }
    }

    @Test
    fun `looked up account is served from memory without being stored`() = runTest {
        var queries = 0
        var saves = 0
        val cache = CachedMatchHistoryProvider(counting { queries++ }, save = { _, _ -> saves++ },
            find = { _, _ -> null }, clock = { 0L }, isTracked = { false })
        assertEquals(summary, cache.recentHistory("107").summaries.single())
        assertEquals(summary, cache.recentHistory("107").summaries.single())
        assertEquals(1, queries)
        assertEquals(0, saves)
    }

    @Test
    fun `signup verification snapshot is stored once the account is tracked`() = runTest {
        var tracked = false
        var queries = 0
        var saves = 0
        val cache = CachedMatchHistoryProvider(counting { queries++ }, save = { _, _ -> saves++ },
            find = { _, _ -> null }, clock = { 0L }, isTracked = { tracked })
        cache.recentHistory("107")
        tracked = true
        cache.recentHistory("107")
        cache.recentHistory("107")
        assertEquals(1, queries)
        assertEquals(1, saves)
    }

    @Test
    fun `failed save is retried by the next read of the same snapshot`() = runTest {
        var queries = 0
        var saves = 0
        val cache = CachedMatchHistoryProvider(counting { queries++ }, save = { _, _ -> if (++saves == 1) error("database down") },
            find = { _, _ -> null }, clock = { 0L }, isTracked = { true })
        assertFailsWith<IllegalStateException> { cache.recentHistory("107") }
        cache.recentHistory("107")
        assertEquals(1, queries)
        assertEquals(2, saves)
    }
}
