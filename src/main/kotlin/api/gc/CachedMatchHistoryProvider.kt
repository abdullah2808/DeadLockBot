package api.gc

import data.MatchCacheRepository
import data.UserRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import models.MatchHistoryDTO

class CachedMatchHistoryProvider(
    private val delegate: MatchHistoryProvider,
    private val save: (String, List<MatchHistoryDTO>) -> Unit = MatchCacheRepository::save,
    private val find: (String, Long) -> MatchHistoryDTO? = MatchCacheRepository::find,
    private val clock: () -> Long = System::currentTimeMillis,
    private val isTracked: (String) -> Boolean = UserRepository::isTracked,
) : MatchHistoryProvider {
    private class Entry(val fetchedAt: Long, val snapshot: MatchHistorySnapshot, var saved: Boolean = false)

    private val mutex = Mutex()
    private val snapshots = object : LinkedHashMap<String, Entry>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > 100
    }

    override suspend fun recentMatchIds(accountId: String): List<Long> = recentHistory(accountId).ids
    override suspend fun recentHistory(accountId: String): MatchHistorySnapshot = mutex.withLock {
        val entry = snapshots[accountId]?.takeIf { clock() - it.fetchedAt < 30_000L }
            ?: Entry(clock(), delegate.recentHistory(accountId)).also { snapshots[accountId] = it }
        // Lookups of other accounts are answered from memory only. Signup verifies before the account
        // is tracked, so the snapshot it fetched is stored by the first read after registration.
        if (!entry.saved && isTracked(accountId)) {
            save(accountId, entry.snapshot.summaries)
            entry.saved = true
        }
        entry.snapshot
    }
    override suspend fun cachedMatch(accountId: String, matchId: Long): MatchHistoryDTO? = find(accountId, matchId)
    override suspend fun metadataDownload(targetAccountId: String?, matchId: Long, tracked: Boolean) =
        delegate.metadataDownload(targetAccountId, matchId, tracked)
    override suspend fun profileCard(accountId: String) = delegate.profileCard(accountId)
    override suspend fun activeMatches() = delegate.activeMatches()
    override fun playingDeadlock(accountId: String) = delegate.playingDeadlock(accountId)
    override fun steamBotIdentity() = delegate.steamBotIdentity()
    override fun steamPlayerName(accountId: String) = delegate.steamPlayerName(accountId)
    override fun friendship(accountId: String) = delegate.friendship(accountId)
    override fun autoAcceptFriendsEnabled() = delegate.autoAcceptFriendsEnabled()
    override fun authorizeSignupFriendRequest(accountId: String) = delegate.authorizeSignupFriendRequest(accountId)
    override fun updateTrackedAccounts(accounts: Set<String>) = delegate.updateTrackedAccounts(accounts)
    override fun trackingDiagnostics() = delegate.trackingDiagnostics()
    override fun close() = delegate.close()
}
