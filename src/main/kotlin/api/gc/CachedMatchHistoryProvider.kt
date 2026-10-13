package api.gc

import data.MatchCacheRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import models.MatchHistoryDTO

class CachedMatchHistoryProvider(
    private val delegate: MatchHistoryProvider,
    private val save: (String, List<MatchHistoryDTO>) -> Unit = MatchCacheRepository::save,
    private val find: (String, Long) -> MatchHistoryDTO? = MatchCacheRepository::find,
    private val clock: () -> Long = System::currentTimeMillis,
) : MatchHistoryProvider {
    private val mutex = Mutex()
    private val snapshots = object : LinkedHashMap<String, Pair<Long, MatchHistorySnapshot>>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, MatchHistorySnapshot>>) = size > 100
    }

    override suspend fun recentMatchIds(accountId: String): List<Long> = recentHistory(accountId).ids
    override suspend fun recentHistory(accountId: String): MatchHistorySnapshot = mutex.withLock {
        val cached = snapshots[accountId]
        if (cached != null && clock() - cached.first < 30_000L) return@withLock cached.second
        val snapshot = delegate.recentHistory(accountId)
        save(accountId, snapshot.summaries)
        snapshots[accountId] = clock() to snapshot
        snapshot
    }
    override suspend fun cachedMatch(accountId: String, matchId: Long): MatchHistoryDTO? = find(accountId, matchId)
}
