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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeadlockClientTest {

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
    fun `getMatchByMatchID returns null instead of throwing on failure`() = runTest {
        val client = mockClient {
            respond(content = "not json", status = HttpStatusCode.InternalServerError)
        }
        val deadlockClient = DeadlockClient(client, retryDelayMillis = 0)

        val result = deadlockClient.getMatchByMatchID(999L)

        assertNull(result)
    }

    @Test
    fun `getMatchByMatchID returns null when the client is already closed`() = runTest {
        val client = mockClient {
            respond(content = "not json", status = HttpStatusCode.OK)
        }
        val deadlockClient = DeadlockClient(client, retryDelayMillis = 0)
        deadlockClient.close()

        val result = deadlockClient.getMatchByMatchID(999L)

        assertNull(result)
    }
}
