package api

import api.gc.MatchHistoryProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import models.MatchDTO

/** One completed document per match; retry failures per participant, since GC access can differ. */
class MatchMetadataService(
    private val gc: MatchHistoryProvider?,
    private val download: suspend (api.gc.GcMetadataDownload) -> MatchDTO,
    private val fallback: suspend (Long) -> MatchDTO?,
    private val find: (Long) -> MatchDTO? = { null },
    private val save: (MatchDTO) -> Unit = {},
    private val knownMatch: (String, Long) -> Boolean = { _, _ -> true },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Key(val account: String, val match: Long)
    private data class Retry(val attempts: Int, val due: Long)
    private val mutex = Mutex()
    private val completed = linkedMapOf<Long, MatchDTO>()
    private val pending = linkedMapOf<Key, Retry>()

    suspend fun metadataFor(account: String, match: Long): MatchDTO? = mutex.withLock {
        require(match > 0)
        val key = Key(account, match)
        (completed.remove(match) ?: find(match))?.let {
            if (valid(it, match)) { remember(it); return@withLock it }
        }
        if ((pending[key]?.due ?: 0) > clock()) return@withLock null
        var fetchedFrom = "API"
        val info = withTimeoutOrNull(30_000) {
            val direct = if (gc != null) attempt("GC", match) {
                gc.metadataDownload(account, match)?.let { download(it) }
            } else null
            direct?.takeIf { valid(it, match) }?.also { fetchedFrom = "Valve GC" }
                ?: attempt("API", match) { fallback(match) }
        }?.takeIf { valid(it, match) }
        if (info != null) {
            // Persist before announcing success; a database failure leaves the work retryable.
            try { save(info) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { defer(key); throw e }
            remember(info)
            println("Cached $fetchedFrom metadata for match $match (${info.matchInfo.players.size} players).")
        } else {
            defer(key)
        }
        info
    }

    /** Continue enrichment after a basic GC summary has already been posted. */
    suspend fun retryPending(tracked: Set<String>) {
        val due = mutex.withLock {
            pending.keys.removeIf { it.account !in tracked || !knownMatch(it.account, it.match) }
            pending.filterValues { it.due <= clock() }.keys.take(2)
        }
        for (key in due) metadataFor(key.account, key.match)
    }

    private fun remember(info: MatchDTO) {
        completed[info.matchInfo.matchId] = info
        pending.keys.removeIf { it.match == info.matchInfo.matchId }
        while (completed.size > 256) completed.remove(completed.keys.first())
    }
    private fun defer(key: Key) {
        val attempts = ((pending[key]?.attempts ?: 0) + 1).coerceAtMost(6)
        val delay = (30_000L shl (attempts - 1)).coerceAtMost(15 * 60_000L)
        pending[key] = Retry(attempts, clock() + delay)
        while (pending.size > 512) pending.remove(pending.keys.first())
    }
    private fun valid(info: MatchDTO, id: Long) = info.matchInfo.let {
        it.matchId == id && it.startTime > 0 && it.durationSeconds != null && it.players.isNotEmpty()
    }
    private suspend fun attempt(source: String, match: Long, fetch: suspend () -> MatchDTO?): MatchDTO? = try {
        fetch()
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        println("$source metadata unavailable for match $match: ${e.message}")
        null
    }
}
