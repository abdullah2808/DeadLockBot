package api

import api.gc.GcMetadataDownload
import api.gc.GcMetadataException
import api.gc.GcMetadataFailure
import api.gc.GcRateLimitedException
import api.gc.GcRequestDeferredException
import api.gc.MatchHistoryProvider
import api.gc.SteamFriendship
import api.gc.ValveMetadataHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import models.MatchDTO

/** Why full metadata could not be returned, ordered from most to least specific. */
enum class MetadataUnavailableReason {
    /** Valve reports that the match has no metadata. */
    INVALID_MATCH,
    /** Valve denied every participant the bot could ask on behalf of. */
    ACCESS_DENIED,
    /** Valve knows the match but has not published its metadata file yet. */
    PROCESSING,
    /** Not stored publicly yet, and the scarce Steam lookups are paced or cooling down. */
    WAITING_FOR_LOOKUP,
    /** Not stored publicly, and no registered player connects the match to the bot's Steam access. */
    NOT_STORED,
    ERROR,
}

sealed interface MetadataLookup {
    data class Found(val match: MatchDTO) : MetadataLookup
    /** [retryInMillis] is when something new will be tried, if known; [retrying] means background enrichment continues. */
    data class Unavailable(
        val reason: MetadataUnavailableReason,
        val retryInMillis: Long? = null,
        val retrying: Boolean = false,
    ) : MetadataLookup
}

/**
 * One completed document per match. Cheap sources (local cache, stored public API, a known Valve download
 * reference) are tried on every lookup. Scarce Steam lookups follow only on a miss: this bot's budgeted GC
 * query first, then deadlock-api's separate Steam-backed quota when the GC cannot be asked or cannot answer.
 * GC queries target a participant, preferring the bot's Steam friends, and a participant Valve denies is not
 * asked again for that match.
 */
class MatchMetadataService(
    private val gc: MatchHistoryProvider?,
    private val download: suspend (GcMetadataDownload) -> MatchDTO,
    private val fallback: suspend (Long) -> MatchDTO?,
    private val steamFallback: (suspend (Long) -> MatchDTO?)? = null,
    private val find: (Long) -> MatchDTO? = { null },
    private val save: (MatchDTO) -> Unit = {},
    private val knownMatch: (String, Long) -> Boolean = { _, _ -> true },
    private val participants: (Long) -> List<String> = { emptyList() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Key(val account: String, val match: Long)
    private data class Retry(val attempts: Int, val due: Long, val outcome: MetadataLookup.Unavailable)
    private class Failure(val reason: MetadataUnavailableReason, val retryInMillis: Long? = null)
    /** Valve answered without a download reference; space further queries for this match. */
    private class GcAnswer(val queries: Int, val nextAt: Long, val reason: MetadataUnavailableReason)

    private val mutex = Mutex()
    // Read without the mutex so cached reports never wait behind a slow Steam lookup.
    private val completed = object : LinkedHashMap<Long, MatchDTO>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, MatchDTO>) = size > 256
    }
    // The rest is guarded by the mutex.
    private val pending = linkedMapOf<Key, Retry>()
    private val references = bounded<Pair<Long, GcMetadataDownload>>()
    private val deniedTargets = bounded<MutableSet<String>>()
    private val invalidMatches = bounded<Unit>()
    private val gcAnswers = bounded<GcAnswer>()

    suspend fun metadataFor(account: String?, match: Long, interactive: Boolean = false, viewer: Boolean = false): MatchDTO? =
        (lookup(account, match, interactive, viewer) as? MetadataLookup.Found)?.match

    /**
     * [account] is whoever the lookup is for, if registered. [viewer] means the account only asked to see the
     * match and is not known to have played it, so it is never assumed to be a participant. [interactive]
     * lookups (a person is waiting) skip the background retry delay; scarce Steam sources keep their budgets.
     */
    suspend fun lookup(account: String?, match: Long, interactive: Boolean = false, viewer: Boolean = false): MetadataLookup {
        require(match > 0)
        cached(match)?.let { return MetadataLookup.Found(it) }
        return mutex.withLock {
            cached(match)?.let { return@withLock MetadataLookup.Found(it) }
            val people = participants(match)
            val player = account?.takeIf { it in people || (people.isEmpty() && !viewer) }
            // Background retries survive only for a participant whose match is retained.
            val key = (player ?: people.firstOrNull() ?: account)?.let { Key(it, match) }
            val waiting = key?.let { pending[it] }
            if (!interactive && waiting != null && waiting.due > clock()) return@withLock waiting.outcome

            val failures = mutableListOf<Failure>()
            var source = "API"
            val started = clock()
            val info = withTimeoutOrNull(LOOKUP_TIMEOUT_MILLIS) {
                fetch(player, people, registered = account != null, match, failures) { source = it }
            }
            if (info == null) {
                val outcome = summarize(failures)
                if (outcome.reason == MetadataUnavailableReason.INVALID_MATCH) pending.keys.removeIf { it.match == match }
                if (key == null || outcome.reason == MetadataUnavailableReason.INVALID_MATCH) return@withLock outcome
                val delay = defer(key, outcome)
                return@withLock outcome.copy(retryInMillis = outcome.retryInMillis ?: delay,
                    retrying = knownMatch(key.account, match))
            }
            // Persist before announcing success; a database failure leaves the work retryable.
            try { save(info) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { key?.let { defer(it, MetadataLookup.Unavailable(MetadataUnavailableReason.ERROR)) }; throw e }
            remember(info)
            references.remove(match)
            deniedTargets.remove(match)
            gcAnswers.remove(match)
            pending.keys.removeIf { it.match == match }
            println("Cached $source metadata for match $match (${info.matchInfo.players.size} players) in ${clock() - started}ms.")
            MetadataLookup.Found(info)
        }
    }

    /**
     * When the earliest background retry is due, so the scheduler can wake for it; null when none is pending.
     * Never waits behind a lookup in progress: it then asks to be checked again shortly.
     */
    fun nextRetryInMillis(): Long? {
        if (!mutex.tryLock()) return BUSY_RECHECK_MILLIS
        try { return pending.values.minOfOrNull { it.due }?.minus(clock()) } finally { mutex.unlock() }
    }

    /** Continue enrichment after a basic GC summary has already been posted. */
    suspend fun retryPending(tracked: Set<String>) {
        val due = mutex.withLock {
            pending.keys.removeIf {
                it.account !in tracked || synchronized(completed) { it.match in completed } || !knownMatch(it.account, it.match)
            }
            pending.filterValues { it.due <= clock() }.keys.take(2)
        }
        for (key in due) lookup(key.account, key.match)
    }

    private suspend fun fetch(player: String?, people: List<String>, registered: Boolean, match: Long,
                              failures: MutableList<Failure>, source: (String) -> Unit): MatchDTO? {
        // disable_steam=true: a stored miss costs no Steam quota.
        attempt("Stored API", match, failures) { fallback(match) }?.let { return it }
        if (match in invalidMatches) return null.also { failures += Failure(MetadataUnavailableReason.INVALID_MATCH) }
        failures += Failure(MetadataUnavailableReason.NOT_STORED)

        // A known reference means Valve answered already; only its file is missing, so spend no Steam quota.
        references[match]?.takeIf { clock() - it.first < REFERENCE_TTL_MILLIS }?.second?.let { reference ->
            source("Valve CDN")
            return attempt("Valve CDN", match, failures) { download(reference) }
        }
        source("Valve CDN")
        freshGc(player, people, registered, match, failures)?.let { return it }
        // Valve already said the match is unpublished or invalid; asking it again through deadlock-api cannot help.
        if (failures.any { it.reason == MetadataUnavailableReason.PROCESSING || it.reason == MetadataUnavailableReason.INVALID_MATCH })
            return null
        return steamFallback?.let { steam ->
            source("Steam-backed API")
            attempt("Steam-backed API", match, failures) { steam(match) }
        }
    }

    private suspend fun freshGc(player: String?, people: List<String>, registered: Boolean, match: Long,
                                failures: MutableList<Failure>): MatchDTO? {
        val gc = gc ?: return null
        // Re-asking Valve every few seconds while it is still processing would only spend the daily budget.
        gcAnswers[match]?.let { answer ->
            val wait = if (answer.queries >= GC_QUERY_SPACING.size) null else answer.nextAt - clock()
            if (wait == null || wait > 0) return null.also { failures += Failure(answer.reason, wait) }
        }
        val refused = deniedTargets[match].orEmpty()
        val targets = gcTargets(gc, player, people, registered).filter { it !in refused }
        val target = targets.firstOrNull()
            ?: return null.also { if (refused.isNotEmpty()) failures += Failure(MetadataUnavailableReason.ACCESS_DENIED) }
        val reference = try {
            gc.metadataDownload(target.ifEmpty { null }, match, tracked = people.isNotEmpty() || player != null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("GC metadata unavailable for match $match: ${e.message}")
            val failure = gcFailure(e, match, target, alternatives = targets.size > 1)
            failures += failure
            // Budget deferrals and rate limits have their own timers; space everything else for this match.
            if (e !is GcRequestDeferredException && e !is GcRateLimitedException &&
                failure.reason in setOf(MetadataUnavailableReason.PROCESSING, MetadataUnavailableReason.ERROR)) {
                val queries = (gcAnswers[match]?.queries ?: 0) + 1
                gcAnswers[match] = GcAnswer(queries, clock() + GC_QUERY_SPACING[(queries - 1).coerceAtMost(GC_QUERY_SPACING.lastIndex)], failure.reason)
            }
            return null
        } ?: return null
        gcAnswers.remove(match)
        if (reference.matchId != match) {
            println("GC returned a reference for match ${reference.matchId} instead of $match.")
            return null.also { failures += Failure(MetadataUnavailableReason.ERROR) }
        }
        references[match] = clock() to reference
        return attempt("Valve CDN", match, failures) { download(reference) }
    }

    /**
     * Participants first, Steam friends of the bot before others, since their privacy is most likely to allow
     * access. An untargeted query, as deadlock-api sends, is the last resort. Lookups unconnected to a
     * registered player never spend this bot's GC budget.
     */
    private fun gcTargets(gc: MatchHistoryProvider, player: String?, people: List<String>, registered: Boolean): List<String> {
        if (people.isEmpty() && !registered) return emptyList()
        val known = people.sortedWith(compareBy({ gc.friendship(it) != SteamFriendship.FRIEND }, { it != player }))
        return known.ifEmpty { listOfNotNull(player) } + UNTARGETED
    }

    private fun gcFailure(e: Exception, match: Long, target: String, alternatives: Boolean) = when (e) {
        is GcRequestDeferredException -> Failure(MetadataUnavailableReason.WAITING_FOR_LOOKUP, e.retryAfterMillis)
        is GcRateLimitedException -> Failure(MetadataUnavailableReason.WAITING_FOR_LOOKUP, e.retryAfterMillis)
        is GcMetadataException -> when (e.failure) {
            GcMetadataFailure.DENIED -> {
                deniedTargets.getOrPut(match) { mutableSetOf() } += target
                Failure(if (alternatives) MetadataUnavailableReason.WAITING_FOR_LOOKUP else MetadataUnavailableReason.ACCESS_DENIED)
            }
            GcMetadataFailure.INVALID_MATCH -> {
                invalidMatches[match] = Unit
                Failure(MetadataUnavailableReason.INVALID_MATCH)
            }
            GcMetadataFailure.IN_FLIGHT, GcMetadataFailure.NOT_READY -> Failure(MetadataUnavailableReason.PROCESSING)
            GcMetadataFailure.BUSY -> Failure(MetadataUnavailableReason.ERROR)
        }
        else -> Failure(MetadataUnavailableReason.ERROR)
    }

    private fun summarize(failures: List<Failure>): MetadataLookup.Unavailable {
        val reason = failures.minOfOrNull { it.reason } ?: MetadataUnavailableReason.ERROR
        return MetadataLookup.Unavailable(reason, failures.filter { it.reason == reason }.mapNotNull { it.retryInMillis }.minOrNull())
    }

    private fun cached(match: Long): MatchDTO? {
        synchronized(completed) { completed[match] }?.let { return it }
        return find(match)?.takeIf { valid(it, match) }?.also(::remember)
    }
    private fun remember(info: MatchDTO) = synchronized(completed) { completed[info.matchInfo.matchId] = info }
    /** Cheap sources are re-checked quickly (15s, 30s, 1m, 2m, …, 15m); a GC that frees up sooner is not missed. */
    private fun defer(key: Key, outcome: MetadataLookup.Unavailable): Long {
        val attempts = ((pending.remove(key)?.attempts ?: 0) + 1).coerceAtMost(7)
        val backoff = (15_000L shl (attempts - 1)).coerceAtMost(15 * 60_000L)
        val delay = outcome.retryInMillis?.takeIf { outcome.reason == MetadataUnavailableReason.WAITING_FOR_LOOKUP }
            ?.coerceIn(5_000L, backoff) ?: backoff
        // Re-inserting rotates retries so one stubborn match cannot monopolize background attempts.
        pending[key] = Retry(attempts, clock() + delay, outcome)
        while (pending.size > 512) pending.remove(pending.keys.first())
        return delay
    }
    private fun valid(info: MatchDTO, id: Long) = info.matchInfo.let {
        it.matchId == id && it.startTime > 0 && it.durationSeconds != null && it.players.isNotEmpty()
    }
    private suspend fun attempt(source: String, match: Long, failures: MutableList<Failure>,
                                fetch: suspend () -> MatchDTO?): MatchDTO? = try {
        fetch()?.takeIf { valid(it, match) }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        println("$source metadata unavailable for match $match: ${e.message}")
        failures += when {
            e is MetadataDeferredException -> Failure(MetadataUnavailableReason.WAITING_FOR_LOOKUP, e.retryAfterMillis)
            e is ValveMetadataHttpException && (e.status == 403 || e.status == 404) -> Failure(MetadataUnavailableReason.PROCESSING)
            else -> Failure(MetadataUnavailableReason.ERROR)
        }
        null
    }

    private companion object {
        const val UNTARGETED = ""
        const val BUSY_RECHECK_MILLIS = 5_000L
        /** Each source has its own timeout; this only stops a lookup that is stuck. */
        const val LOOKUP_TIMEOUT_MILLIS = 120_000L
        /** A metadata salt does not change, and Valve keeps the file long after publishing it. */
        const val REFERENCE_TTL_MILLIS = 24 * 60 * 60_000L
        /** Gaps between GC queries for one match that Valve has not finished; after the last, only cheap sources. */
        val GC_QUERY_SPACING = longArrayOf(60_000L, 3 * 60_000L, 10 * 60_000L, 30 * 60_000L, 60 * 60_000L, 60 * 60_000L)
        fun <V> bounded() = object : LinkedHashMap<Long, V>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, V>) = size > 512
        }
    }
}
