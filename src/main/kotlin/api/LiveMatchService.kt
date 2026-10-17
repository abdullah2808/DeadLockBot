package api

import api.gc.MatchHistoryProvider
import api.gc.ProfileCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import models.ActiveMatchDTO

/**
 * Shares the Watch-tab live list and profile cards between commands, so repeated `/live` and `/profile` calls
 * don't each cost a GC round trip. Live matches come from the GC, then deadlock-api's copy of the same list.
 */
class LiveMatchService(
    private val gc: MatchHistoryProvider?,
    private val apiActiveMatches: (suspend () -> List<ActiveMatchDTO>)?,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private companion object {
        const val LIVE_TTL_MS = 60_000L
        const val PROFILE_TTL_MS = 5 * 60_000L
    }
    data class Snapshot(val matches: List<ActiveMatchDTO>, val fromGc: Boolean)

    private val liveMutex = Mutex()
    private var live: Pair<Long, Snapshot>? = null
    private val profileMutex = Mutex()
    private val profiles = object : LinkedHashMap<String, Pair<Long, ProfileCard>>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, ProfileCard>>) = size > 200
    }

    /** Throws only when every source failed, so an empty list always means nobody listed is live. */
    suspend fun activeMatches(): Snapshot = liveMutex.withLock {
        live?.let { (at, snapshot) -> if (clock() - at < LIVE_TTL_MS) return@withLock snapshot }
        var error: Exception? = null
        val fromGc = try { gc?.activeMatches() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            println("GC active matches unavailable, trying deadlock-api: ${e.message}")
            error = e
            null
        }
        val snapshot = fromGc?.let { Snapshot(it, fromGc = true) } ?: apiActiveMatches?.let { fetch ->
            try { Snapshot(fetch(), fromGc = false) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = e
                null
            }
        } ?: throw error ?: IllegalStateException("No live match source is configured")
        live = clock() to snapshot
        snapshot
    }

    /** The listed live match containing [accountId], if any. */
    suspend fun findLive(accountId: Long): ActiveMatchDTO? =
        activeMatches().matches.firstOrNull { match -> match.players.any { it.accountId == accountId } }

    suspend fun profileCard(accountId: String): ProfileCard? {
        profileMutex.withLock { profiles[accountId] }?.let { (at, card) -> if (clock() - at < PROFILE_TTL_MS) return card }
        val card = gc?.profileCard(accountId) ?: return null
        profileMutex.withLock { profiles[accountId] = clock() to card }
        return card
    }
}
