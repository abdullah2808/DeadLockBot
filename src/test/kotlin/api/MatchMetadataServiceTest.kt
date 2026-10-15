package api

import api.gc.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import models.*
import java.io.IOException
import kotlin.test.*

class MatchMetadataServiceTest {
    @Test fun `shared GC cooldown preserves API fallback and cached reports across accounts and matches`() = runTest {
        var now = 0L
        var sends = 0
        var apiCalls = 0
        val gate = GcRequestGate { now }
        val service = MatchMetadataService(gc { _, id ->
            gate.request(1, { it: Int -> it }) { sends++; if (now == 0L) 5 else 1 }
            GcMetadataDownload(id, 1, 2)
        }, download = { match(it.matchId) }, fallback = { apiCalls++; if (it == 99L) match(it) else null }, clock = { now })
        assertEquals(match(), service.metadataFor("1", 99))
        assertEquals(match(), service.metadataFor("2", 99))
        assertNull(service.metadataFor("2", 100))
        assertEquals(1, sends)
        assertEquals(2, apiCalls)
        now = 60_000
        service.retryPending(setOf("2"))
        assertEquals(match(100), service.metadataFor("2", 100))
        assertEquals(2, sends)
        assertEquals(3, apiCalls)
    }
    @Test fun `stored API match avoids GC even when Steam is rate limited`() = runTest {
        var queries = 0
        var downloads = 0
        val service = MatchMetadataService(gc { _, _ -> queries++; throw GcRateLimitedException(60_000) },
            download = { downloads++; match() }, fallback = { match(it) })
        assertEquals(match(), service.metadataFor("1", 99))
        assertEquals(0, queries)
        assertEquals(0, downloads)
    }

    @Test fun `stored API cancellation propagates before GC is queried`() = runTest {
        var queries = 0
        val service = MatchMetadataService(gc { _, _ -> queries++; null },
            download = { match() }, fallback = { throw CancellationException("cancelled") })
        assertFailsWith<CancellationException> { service.metadataFor("1", 99) }
        assertEquals(0, queries)
    }

    @Test fun `CDN retries reuse one GC reference across participants`() = runTest {
        var queries = 0
        var downloads = 0
        val service = MatchMetadataService(gc { _, id -> queries++; GcMetadataDownload(id, 1, 2) },
            download = { if (++downloads == 1) throw IOException("CDN not ready"); match(it.matchId) },
            fallback = { null }, clock = { 0L })
        assertNull(service.metadataFor("1", 99))
        assertEquals(match(), service.metadataFor("2", 99))
        assertEquals(1, queries)
        assertEquals(2, downloads)
    }

    @Test fun `invalid stored data is ignored and GC still supplies the correct match`() = runTest {
        val service = MatchMetadataService(gc { _, id -> GcMetadataDownload(id, 1, 2) },
            download = { match(it.matchId) }, fallback = { match(100) })
        assertEquals(match(), service.metadataFor("1", 99))
    }
    @Test fun `temporary persistence failure leaves enrichment pending`() = runTest {
        var now = 0L
        var saves = 0
        val service = MatchMetadataService(gc { _, id -> GcMetadataDownload(id, 1, 2) }, download = { match() },
            fallback = { null }, save = { if (++saves == 1) throw IOException("database unavailable") }, clock = { now })
        assertFailsWith<IOException> { service.metadataFor("1", 99) }
        now = 30000
        service.retryPending(setOf("1"))
        assertEquals(2, saves)
        assertEquals(match(), service.metadataFor("1", 99))
    }
    private fun match(id: Long = 99) = MatchDTO(MatchInfoDTO(matchId = id, startTime = 123,
        durationSeconds = 1800, winningTeam = 1, players = listOf(Players(1, heroId = 88), Players(2, heroId = 85))))
    private fun gc(friends: Set<String> = emptySet(), fetch: suspend (String?, Long) -> GcMetadataDownload?) = object : MatchHistoryProvider {
        override suspend fun recentMatchIds(accountId: String) = emptyList<Long>()
        override suspend fun metadataDownload(targetAccountId: String?, matchId: Long, tracked: Boolean) = fetch(targetAccountId, matchId)
        override fun friendship(accountId: String) = if (accountId in friends) SteamFriendship.FRIEND else SteamFriendship.NONE
    }
    @Test fun `simultaneous party lookups download and save a match only once`() = runTest {
        var queries = 0
        var downloads = 0
        var saves = 0
        val service = MatchMetadataService(gc { _, id -> queries++; GcMetadataDownload(id, 1, 2) },
            download = { downloads++; delay(100); match() }, fallback = { null }, save = { saves++ })
        val a = async { service.metadataFor("1", 99) }
        val b = async { service.metadataFor("2", 99) }
        assertEquals(match(), a.await())
        assertEquals(match(), b.await())
        assertEquals(1, queries)
        assertEquals(1, downloads)
        assertEquals(1, saves)
    }
    @Test fun `persisted metadata survives restart without contacting GC or API`() = runTest {
        val service = MatchMetadataService(gc { _, _ -> error("Unexpected GC query") },
            download = { error("Unexpected download") }, fallback = { error("Unexpected API query") }, find = { match() })
        assertEquals(match(), service.metadataFor("2", 99))
    }
    @Test fun `failure cooldown is per participant and successful party lookup clears retries`() = runTest {
        val queried = mutableListOf<String?>()
        val service = MatchMetadataService(gc { account, id ->
            queried += account
            if (account == "1") throw GcMetadataException(id, GcMetadataFailure.DENIED, "k_eResult_InvalidPermission")
            GcMetadataDownload(id, 1, 2)
        }, download = { match() }, fallback = { null }, clock = { 0L })
        assertNull(service.metadataFor("1", 99))
        assertNull(service.metadataFor("1", 99))
        assertEquals(match(), service.metadataFor("2", 99))
        assertEquals(match(), service.metadataFor("1", 99))
        assertEquals(listOf<String?>("1", "2"), queried)
    }
    @Test fun `background retry enriches after basic summary is delivered`() = runTest {
        var now = 0L
        var ready = false
        var saved: MatchDTO? = null
        val service = MatchMetadataService(gc { _, id -> if (ready) GcMetadataDownload(id, 1, 2) else null },
            download = { match() }, fallback = { null }, save = { saved = it }, clock = { now })
        assertNull(service.metadataFor("1", 99))
        ready = true
        service.retryPending(setOf("1"))
        assertNull(saved)
        now = 30000
        service.retryPending(setOf("1"))
        assertEquals(match(), saved)
    }
    @Test fun `unregistered or trimmed matches stop background retries`() = runTest {
        var now = 0L
        var queries = 0
        val service = MatchMetadataService(gc { _, _ -> queries++; null }, download = { match() },
            fallback = { null }, knownMatch = { _, _ -> false }, clock = { now })
        service.metadataFor("1", 99)
        service.metadataFor("2", 100)
        now = 30000
        service.retryPending(setOf("1"))
        assertEquals(2, queries)
    }
    @Test fun `GC succeeds even when API fallback is rate limited and API handles a GC failure`() = runTest {
        var apiCalls = 0
        val direct = MatchMetadataService(gc { _, id -> GcMetadataDownload(id, 1, 2) }, download = { match() },
            fallback = { apiCalls++; null })
        assertNotNull(direct.metadataFor("1", 99))
        assertEquals(1, apiCalls)
        val fallback = MatchMetadataService(gc { _, _ -> throw IOException("GC busy") }, download = { match() },
            fallback = { apiCalls++; match() })
        assertNotNull(fallback.metadataFor("1", 99))
        assertEquals(2, apiCalls)
    }
    @Test fun `wrong IDs and incomplete metadata are never saved`() = runTest {
        var saves = 0
        val service = MatchMetadataService(gc { _, id -> GcMetadataDownload(id, 1, 2) }, download = { match(100) },
            fallback = { MatchDTO(MatchInfoDTO(matchId = 99, players = emptyList())) }, save = { saves++ })
        assertNull(service.metadataFor("1", 99))
        assertEquals(0, saves)
    }
    @Test fun `GC cancellation propagates without caching failure`() = runTest {
        var queries = 0
        var apiCalls = 0
        val service = MatchMetadataService(gc { _, id ->
            if (++queries == 1) throw CancellationException("cancelled")
            GcMetadataDownload(id, 1, 2)
        }, download = { match() }, fallback = { apiCalls++; null })
        assertFailsWith<CancellationException> { service.metadataFor("1", 99) }
        assertNotNull(service.metadataFor("1", 99))
        assertEquals(2, apiCalls)
    }

    private fun unavailable(result: MetadataLookup) = result as MetadataLookup.Unavailable

    @Test fun `on-demand lookups skip the background delay and see newly stored matches`() = runTest {
        var stored: MatchDTO? = null
        var queries = 0
        val service = MatchMetadataService(gc { _, _ -> queries++; throw GcRequestDeferredException(600_000) },
            download = { match() }, fallback = { stored }, clock = { 0L })
        assertNull(service.metadataFor("1", 99))
        stored = match()
        assertNull(service.metadataFor("1", 99))
        assertEquals(match(), service.metadataFor("1", 99, interactive = true))
        assertEquals(1, queries)
    }

    @Test fun `GC targets a befriended participant instead of whoever asked`() = runTest {
        val targets = mutableListOf<String?>()
        val service = MatchMetadataService(gc(friends = setOf("3")) { target, id -> targets += target; GcMetadataDownload(id, 1, 2) },
            download = { match() }, fallback = { null }, participants = { listOf("2", "3") })
        assertEquals(match(), service.metadataFor("9", 99, interactive = true))
        assertEquals(listOf<String?>("3"), targets)
    }

    @Test fun `a denied participant is not asked again and exhausted access is reported`() = runTest {
        val targets = mutableListOf<String?>()
        val service = MatchMetadataService(gc { target, id ->
            targets += target
            throw GcMetadataException(id, GcMetadataFailure.DENIED, "k_eResult_InvalidPermission")
        }, download = { match() }, fallback = { null }, participants = { listOf("2") })
        assertEquals(MetadataUnavailableReason.WAITING_FOR_LOOKUP, unavailable(service.lookup("2", 99, interactive = true)).reason)
        repeat(2) {
            assertEquals(MetadataUnavailableReason.ACCESS_DENIED, unavailable(service.lookup("2", 99, interactive = true)).reason)
        }
        assertEquals(listOf<String?>("2", null), targets)
    }

    @Test fun `invalid matches stop every Steam lookup and background retry`() = runTest {
        var queries = 0
        var steamCalls = 0
        var now = 0L
        val service = MatchMetadataService(gc { _, id -> queries++; throw GcMetadataException(id, GcMetadataFailure.INVALID_MATCH, "invalid") },
            download = { match() }, fallback = { null }, steamFallback = { steamCalls++; null }, clock = { now })
        repeat(2) { assertEquals(MetadataUnavailableReason.INVALID_MATCH, unavailable(service.lookup("1", 99, interactive = true)).reason) }
        now = 60 * 60_000L
        service.retryPending(setOf("1"))
        assertEquals(1, queries)
        assertEquals(0, steamCalls)
    }

    @Test fun `unpublished Valve files report processing and retry without another Steam query`() = runTest {
        var queries = 0
        var steamCalls = 0
        var published = false
        val service = MatchMetadataService(gc { _, id -> queries++; GcMetadataDownload(id, 1, 2) },
            download = { if (!published) throw ValveMetadataHttpException(404); match() },
            fallback = { null }, steamFallback = { steamCalls++; null })
        val waiting = unavailable(service.lookup("1", 99, interactive = true))
        assertEquals(MetadataUnavailableReason.PROCESSING, waiting.reason)
        assertTrue(waiting.retrying)
        assertEquals(15_000L, waiting.retryInMillis)
        published = true
        assertEquals(match(), service.metadataFor("1", 99, interactive = true))
        assertEquals(1, queries)
        assertEquals(0, steamCalls)
    }

    @Test fun `GC in-flight answers are reported as processing`() = runTest {
        val service = MatchMetadataService(gc { _, id -> throw GcMetadataException(id, GcMetadataFailure.IN_FLIGHT, "in flight") },
            download = { match() }, fallback = { null })
        assertEquals(MetadataUnavailableReason.PROCESSING, unavailable(service.lookup("1", 99, interactive = true)).reason)
    }

    @Test fun `the bot's own GC goes first and deadlock-api's Steam lookup covers a deferred GC`() = runTest {
        var steamCalls = 0
        var deferred = false
        val service = MatchMetadataService(gc { _, id -> if (deferred) throw GcRequestDeferredException(20_000); GcMetadataDownload(id, 1, 2) },
            download = { match(it.matchId) }, fallback = { null }, steamFallback = { steamCalls++; match(it) })
        assertEquals(match(), service.metadataFor("1", 99))
        assertEquals(0, steamCalls)
        deferred = true
        assertEquals(match(100), service.metadataFor("1", 100))
        assertEquals(1, steamCalls)
    }

    @Test fun `Valve is not re-asked about an unfinished match until its spacing elapses`() = runTest {
        var now = 0L
        var queries = 0
        var steamCalls = 0
        val service = MatchMetadataService(gc { _, id ->
            queries++
            if (now < 60_000) throw GcMetadataException(id, GcMetadataFailure.IN_FLIGHT, "k_eResult_MatchInFlight")
            GcMetadataDownload(id, 1, 2)
        }, download = { match() }, fallback = { null }, steamFallback = { steamCalls++; null }, clock = { now })
        assertEquals(MetadataUnavailableReason.PROCESSING, unavailable(service.lookup("1", 99, interactive = true)).reason)
        now = 30_000
        val waiting = unavailable(service.lookup("1", 99, interactive = true))
        assertEquals(MetadataUnavailableReason.PROCESSING, waiting.reason)
        assertEquals(30_000L, waiting.retryInMillis)
        assertEquals(1, queries)
        now = 60_000
        assertEquals(match(), service.metadataFor("1", 99, interactive = true))
        assertEquals(2, queries)
        assertEquals(0, steamCalls)
    }

    @Test fun `a registered viewer of an untracked match is not assumed to have played it`() = runTest {
        val targets = mutableListOf<String?>()
        val trackedFlags = mutableListOf<Boolean>()
        val provider = object : MatchHistoryProvider {
            override suspend fun recentMatchIds(accountId: String) = emptyList<Long>()
            override suspend fun metadataDownload(targetAccountId: String?, matchId: Long, tracked: Boolean): GcMetadataDownload {
                targets += targetAccountId
                trackedFlags += tracked
                return GcMetadataDownload(matchId, 1, 2)
            }
        }
        val service = MatchMetadataService(provider, download = { match() }, fallback = { null })
        assertEquals(match(), service.metadataFor("9", 99, interactive = true, viewer = true))
        // Untargeted, and only from the budget's unreserved share.
        assertEquals(listOf<String?>(null), targets)
        assertEquals(listOf(false), trackedFlags)
    }

    @Test fun `the scheduler learns when the next background retry is due`() = runTest {
        var now = 0L
        val service = MatchMetadataService(gc { _, _ -> null }, download = { match() }, fallback = { null }, clock = { now })
        assertNull(service.nextRetryInMillis())
        assertNull(service.metadataFor("1", 99))
        now = 5_000
        assertEquals(10_000L, service.nextRetryInMillis())
    }

    @Test fun `paced Steam sources report the earliest next lookup`() = runTest {
        val service = MatchMetadataService(gc { _, _ -> throw GcRequestDeferredException(25 * 60_000L) },
            download = { match() }, fallback = { null }, steamFallback = { throw MetadataDeferredException(5 * 60_000L) })
        val outcome = unavailable(service.lookup("1", 99, interactive = true))
        assertEquals(MetadataUnavailableReason.WAITING_FOR_LOOKUP, outcome.reason)
        assertEquals(5 * 60_000L, outcome.retryInMillis)
    }

    @Test fun `unregistered lookups of untracked matches never spend the GC budget`() = runTest {
        var queries = 0
        val service = MatchMetadataService(gc { _, _ -> queries++; null }, download = { match() }, fallback = { null })
        val outcome = unavailable(service.lookup(null, 99, interactive = true))
        assertEquals(MetadataUnavailableReason.NOT_STORED, outcome.reason)
        assertFalse(outcome.retrying)
        assertEquals(0, queries)
    }

    @Test fun `unregistered viewers of a tracked match use the participant's access`() = runTest {
        val targets = mutableListOf<String?>()
        val service = MatchMetadataService(gc { target, id -> targets += target; GcMetadataDownload(id, 1, 2) },
            download = { match() }, fallback = { null }, participants = { listOf("2") })
        assertEquals(match(), service.metadataFor(null, 99, interactive = true))
        assertEquals(listOf<String?>("2"), targets)
    }

    @Test fun `cached reports do not wait behind a slow Steam lookup`() = runTest {
        val steam = CompletableDeferred<Unit>()
        val service = MatchMetadataService(gc { _, id -> steam.await(); GcMetadataDownload(id, 1, 2) },
            download = { match(it.matchId) }, fallback = { null }, find = { if (it == 100L) match(100) else null })
        val slow = async { service.metadataFor("1", 99) }
        runCurrent()
        assertEquals(match(100), service.metadataFor("1", 100))
        steam.complete(Unit)
        assertEquals(match(), slow.await())
    }
}
