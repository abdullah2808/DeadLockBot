package api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeadlockClientTest {
    @Test fun `stored metadata load shedding respects Retry-After before another match lookup`() = runTest {
        var now = 0L
        var requests = 0
        val client = mockClient {
            requests++
            respond("{}", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.RetryAfter, "120"))
        }
        val api = DeadlockClient(client, 0, MetadataRequestGate(clock = { now }))
        assertNull(api.getMatchByMatchID(99))
        now = 119_999
        assertNull(api.getMatchByMatchID(100))
        assertEquals(1, requests)
        now = 120_000
        assertNull(api.getMatchByMatchID(100))
        assertEquals(2, requests)
        api.close()
    }
    @Test fun `metadata requests disable Steam fallback to avoid consuming GC quota`() = runTest {
        val client = mockClient { request ->
            assertEquals("true", request.url.parameters["disable_steam"])
            assertEquals("/v1/matches/99/metadata", request.url.encodedPath)
            respond("{}", HttpStatusCode.NotFound)
        }
        val api = DeadlockClient(client, 0, MetadataRequestGate())
        assertNull(api.getMatchByMatchID(99))
        api.close()
    }

    @Test fun `Steam-backed metadata lets deadlock-api ask Steam, sends the key and is paced`() = runTest {
        var now = 0L
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val client = mockClient { request -> requests += request; respond("{}", HttpStatusCode.NotFound) }
        val api = DeadlockClient(client, 0, MetadataRequestGate(clock = { now }, steamIntervalMillis = 60_000), apiKey = "key")
        assertNull(api.getMatchFromSteam(99))
        assertNull(requests.single().url.parameters["disable_steam"])
        assertEquals("key", requests.single().headers["X-API-Key"])
        assertEquals(60_000L, assertFailsWith<MetadataDeferredException> { api.getMatchFromSteam(100) }.retryAfterMillis)
        now = 60_000
        assertNull(api.getMatchFromSteam(100))
        assertEquals(2, requests.size)
    }

    @Test fun `a Steam-backed rate limit does not pause stored metadata lookups`() = runTest {
        var stored = 0
        val client = mockClient { request ->
            if (request.url.parameters["disable_steam"] == "true") { stored++; respond("{}", HttpStatusCode.NotFound) }
            else respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "3600"))
        }
        val api = DeadlockClient(client, 0, MetadataRequestGate(clock = { 0L }, steamIntervalMillis = 0))
        assertNull(api.getMatchFromSteam(99))
        assertEquals(3_600_000L, assertFailsWith<MetadataDeferredException> { api.getMatchFromSteam(100) }.retryAfterMillis)
        assertNull(api.getMatchByMatchID(99))
        assertNull(api.getMatchByMatchID(100))
        assertEquals(2, stored)
    }

    private val sampleMatchHistoryJson = """
        [
          {
            "account_id": 1,
            "denies": 2,
            "game_mode": 1,
            "hero_id": 5,
            "hero_level": 10,
            "last_hits": 100,
            "match_duration_s": 1800,
            "match_id": 12345,
            "match_mode": 1,
            "match_result": 0,
            "net_worth": 20000,
            "objectives_mask_team0": 0,
            "objectives_mask_team1": 0,
            "player_assists": 3,
            "player_deaths": 4,
            "player_kills": 10,
            "player_team": 0,
            "start_time": 1700000000
          }
        ]
    """.trimIndent()

    private val sampleActiveMatchesJson = """
        [
          {
            "match_id": 95217129,
            "start_time": 1784798737,
            "players": [
              { "account_id": 100583809, "team": 0, "hero_id": 4 },
              { "account_id": 993986242, "team": 1, "hero_id": 19 }
            ]
          }
        ]
    """.trimIndent()

    private fun mockClient(handler: MockRequestHandler): HttpClient =
        HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

    @Test
    fun `getActiveMatches parses live matches and player account ids`() = runTest {
        val client = mockClient {
            respond(
                content = sampleActiveMatchesJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val deadlockClient = DeadlockClient(client, retryDelayMillis = 0)

        val result = deadlockClient.getActiveMatches()

        assertEquals(1, result.size)
        assertEquals(95217129L, result[0].matchId)
        assertEquals(2, result[0].players.size)
        assertEquals(100583809L, result[0].players[0].accountId)
    }

    @Test
    fun `getActiveMatches throws after exhausting retries`() = runTest {
        val client = mockClient {
            respond(content = "", status = HttpStatusCode.InternalServerError)
        }
        val deadlockClient = DeadlockClient(client, retryDelayMillis = 0)

        assertFailsWith<RuntimeException> {
            deadlockClient.getActiveMatches()
        }
    }

    @Test
    fun `getRecentMatch returns matches on first success`() = runTest {
        var callCount = 0
        val client = mockClient {
            callCount++
            respond(
                content = sampleMatchHistoryJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val deadlockClient = DeadlockClient(client, retryDelayMillis = 0)

        val result = deadlockClient.getRecentMatch("123")

        assertEquals(1, callCount)
        assertEquals(1, result.size)
        assertEquals(12345L, result[0].matchId)
    }

    @Test
    fun `getRecentMatch retries after a 429 and then succeeds`() = runTest {
        var callCount = 0
        val client = mockClient {
            callCount++
            if (callCount < 2) {
                respond(content = "", status = HttpStatusCode.TooManyRequests)
            } else {
                respond(
                    content = sampleMatchHistoryJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        }
        val deadlockClient = DeadlockClient(client, retryDelayMillis = 0)

        val result = deadlockClient.getRecentMatch("123")

        assertEquals(2, callCount)
        assertEquals(1, result.size)
    }

    @Test
    fun `getRecentMatch forces a refetch only on the final attempt`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val client = mockClient { request ->
            requestedUrls.add(request.url.toString())
            respond(content = "", status = HttpStatusCode.TooManyRequests)
        }
        val deadlockClient = DeadlockClient(client, retryDelayMillis = 0)

        assertFailsWith<RuntimeException> {
            deadlockClient.getRecentMatch("123")
        }

        assertEquals(3, requestedUrls.size)
        assertTrue(requestedUrls[0].endsWith("/match-history"))
        assertTrue(requestedUrls[1].endsWith("/match-history"))
        assertTrue(requestedUrls[2].contains("force_refetch=true"))
    }

    @Test
    fun `getRecentMatch throws after exhausting all retries`() = runTest {
        val client = mockClient {
            respond(content = "", status = HttpStatusCode.InternalServerError)
        }
        val deadlockClient = DeadlockClient(client, retryDelayMillis = 0)

        assertFailsWith<RuntimeException> {
            deadlockClient.getRecentMatch("123")
        }
    }

    @Test
    fun `completed metadata is fetched once across clients sharing a match`() = runTest {
        var requests = 0
        val client = mockClient {
            requests++
            respond("""{"match_info":{"match_id":99,"duration_s":1800,"winning_team":1,"players":[]}}""", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val gate = MetadataRequestGate()
        val first = DeadlockClient(client, 0, gate)
        val second = DeadlockClient(client, 0, gate)
        assertEquals(99L, first.getMatchByMatchID(99)?.matchInfo?.matchId)
        assertEquals(99L, second.getMatchByMatchID(99)?.matchInfo?.matchId)
        assertEquals(1, requests)
    }

    @Test
    fun `metadata honors Retry-After across clients sharing the same gate`() = runTest {
        var now = 0L
        var requests = 0
        val gate = MetadataRequestGate(clock = { now })
        val client = mockClient {
            requests++
            if (requests == 1) respond("{}", HttpStatusCode.TooManyRequests,
                headersOf(HttpHeaders.RetryAfter, "120"))
            else respond("""{"match_info":{"match_id":99,"players":[]}}""", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val first = DeadlockClient(client, 0, gate)
        val second = DeadlockClient(client, 0, gate)

        assertNull(first.getMatchByMatchID(99))
        now = 119_000L
        assertNull(second.getMatchByMatchID(99))
        assertEquals(1, requests)
        now = 120_000L
        assertEquals(99L, second.getMatchByMatchID(99)?.matchInfo?.matchId)
        assertEquals(2, requests)
    }

    @Test
    fun `metadata rate limiting backs off when Retry-After is missing`() = runTest {
        var now = 0L
        var requests = 0
        val client = mockClient { requests++; respond("{}", HttpStatusCode.TooManyRequests) }
        val bot = DeadlockClient(client, 0, MetadataRequestGate(clock = { now }))

        assertNull(bot.getMatchByMatchID(99))
        now = 59_000L
        assertNull(bot.getMatchByMatchID(99))
        assertEquals(1, requests)
        now = 60_000L
        assertNull(bot.getMatchByMatchID(99))
        now = 179_000L
        assertNull(bot.getMatchByMatchID(99))
        assertEquals(2, requests)
    }

    @Test
    fun `metadata error response is not deserialized as match data`() = runTest {
        val client = mockClient {
            respond(
                content = """{"status":503,"error":"Failed to fetch data from Steam. Retry your request later."}""",
                status = HttpStatusCode.ServiceUnavailable,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val deadlockClient = DeadlockClient(client, retryDelayMillis = 0)

        assertNull(deadlockClient.getMatchByMatchID(115696167L))
    }

    @Test
    fun `metadata can succeed on a later polling attempt`() = runTest {
        var attempts = 0
        val client = mockClient {
            if (++attempts == 1) {
                respond("{}", HttpStatusCode.ServiceUnavailable)
            } else {
                respond(
                    content = """{"match_info":{"match_id":99,"players":[]}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        }
        val deadlockClient = DeadlockClient(client, retryDelayMillis = 0)

        assertNull(deadlockClient.getMatchByMatchID(99L))
        assertEquals(99L, deadlockClient.getMatchByMatchID(99L)?.matchInfo?.matchId)
    }

    @Test
    fun `getMatchByMatchID returns null instead of throwing on failure`() = runTest {
        val client = mockClient {
            respond(content = "not json", status = HttpStatusCode.InternalServerError)
        }
        val deadlockClient = DeadlockClient(client, retryDelayMillis = 0)

        val result = deadlockClient.getMatchByMatchID(999L)

        assertNull(result)
    }

    @Test
    fun `getMatchByMatchID propagates cancellation when the client is closed`() = runTest {
        val client = mockClient {
            respond(content = "not json", status = HttpStatusCode.OK)
        }
        val deadlockClient = DeadlockClient(client, retryDelayMillis = 0)
        deadlockClient.close()

        assertFailsWith<CancellationException> { deadlockClient.getMatchByMatchID(999L) }
    }
}
