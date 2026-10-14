package api

import api.gc.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
        assertEquals(2, apiCalls)
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
    private fun gc(fetch: suspend (String, Long) -> GcMetadataDownload?) = object : MatchHistoryProvider {
        override suspend fun recentMatchIds(accountId: String) = emptyList<Long>()
        override suspend fun metadataDownload(accountId: String, matchId: Long) = fetch(accountId, matchId)
    }
    @Test fun `simultaneous party lookups download and save a match only once`() = runTest {
        var queries = 0
        var downloads = 0
        var saves = 0
        val service = MatchMetadataService(gc { _, id -> queries++; GcMetadataDownload(id, 1, 2) },
            download = { downloads++; delay(100); match() }, fallback = { error("API should not be needed") }, save = { saves++ })
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
        val queried = mutableListOf<String>()
        val service = MatchMetadataService(gc { account, id ->
            queried += account
            if (account == "1") throw IOException("invalid permission")
            GcMetadataDownload(id, 1, 2)
        }, download = { match() }, fallback = { null }, clock = { 0L })
        assertNull(service.metadataFor("1", 99))
        assertNull(service.metadataFor("1", 99))
        assertEquals(match(), service.metadataFor("2", 99))
        assertEquals(match(), service.metadataFor("1", 99))
        assertEquals(listOf("1", "2"), queried)
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
        assertEquals(0, apiCalls)
        val fallback = MatchMetadataService(gc { _, _ -> throw IOException("GC busy") }, download = { match() },
            fallback = { apiCalls++; match() })
        assertNotNull(fallback.metadataFor("1", 99))
        assertEquals(1, apiCalls)
    }
    @Test fun `wrong IDs and incomplete metadata are never saved`() = runTest {
        var saves = 0
        val service = MatchMetadataService(gc { _, id -> GcMetadataDownload(id, 1, 2) }, download = { match(100) },
            fallback = { MatchDTO(MatchInfoDTO(matchId = 99, players = emptyList())) }, save = { saves++ })
        assertNull(service.metadataFor("1", 99))
        assertEquals(0, saves)
    }
    @Test fun `cancellation propagates without caching failure or calling fallback`() = runTest {
        var queries = 0
        var apiCalls = 0
        val service = MatchMetadataService(gc { _, id ->
            if (++queries == 1) throw CancellationException("cancelled")
            GcMetadataDownload(id, 1, 2)
        }, download = { match() }, fallback = { apiCalls++; null })
        assertFailsWith<CancellationException> { service.metadataFor("1", 99) }
        assertNotNull(service.metadataFor("1", 99))
        assertEquals(0, apiCalls)
    }
}
