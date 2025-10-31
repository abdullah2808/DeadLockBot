package api


import dev.kord.core.kordLogger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import models.MatchDTO
import models.MatchHistoryDTO

class DeadlockClient() {

    val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
    }

    suspend fun getRecentMatch(accountId: String): MatchHistoryDTO {
        var url = "https://api.deadlock-api.com/v1/players/$accountId/match-history"
        val maxRetries = 3
        val delayMillis = 10_000L // 10 seconds

        var lastError: Throwable? = null
        // add a force refresh param on the last try
        repeat(maxRetries) { attempt ->
            try {
                if (attempt == 2) {
                    println("Forcing Refresh for: $accountId")
                    url = "$url?force_refetch=true&"
                }
                val response: HttpResponse = client.get(url)
                if (response.status == HttpStatusCode.TooManyRequests) {
                    // Handle rate limiting explicitly
                    println("Received 429 Too Many Requests on attempt: ${attempt + 1} for: $accountId ")
                } else if (response.status.isSuccess()) {
                    val matchResponse: List<MatchHistoryDTO> = response.body()
                    if (matchResponse.isNotEmpty()) {
                        println("Got match for: $accountId")
                        return matchResponse[0]
                    } else {
                        throw IllegalStateException("No matches found in the response for account $accountId.")
                    }
                } else {
                    println("Unexpected status ${response.status} on attempt ${attempt + 1}. Retrying...")
                }
            } catch (e: Exception) {
                lastError = e
                println("Attempt ${attempt + 1} failed: ${e.message}")
            }

            // Only delay if not on the last attempt
            if (attempt < maxRetries - 1) {
                println("Waiting ${delayMillis / 1000} seconds before retrying...")
                delay(delayMillis)
            }
        }
        throw RuntimeException(
            "Failed to fetch recent match for account $accountId after $maxRetries attempts. " +
                    "Last error: ${lastError?.message ?: "Unknown error"}"
        )
    }

    suspend fun getMatchByMatchID(matchId: Long): MatchDTO? {
        val url = "https://api.deadlock-api.com/v1/matches/$matchId/metadata"
        return try {
            client.get(url).body<MatchDTO>()
        }  catch (e: Exception) {
            println("Error fetching match $matchId: ${e.message}")
            null
        }
    }

    suspend fun close() {
        client.close()
    }
}