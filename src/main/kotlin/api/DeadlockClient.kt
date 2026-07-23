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

    suspend fun getRecentMatch(accountId: String): List<MatchHistoryDTO> {
        var url = "https://api.deadlock-api.com/v1/players/$accountId/match-history"
        val maxRetries = 3
        val delayMillis = 10_000L // 10 seconds

        var lastError: Throwable? = null
        // add a force refresh param on the last try
        repeat(maxRetries) { attempt ->
            try {
                if (attempt == 2) {
                    println("Forcing Refresh for: $accountId")
                    url = "$url?force_refetch=true"
                }
                val response: HttpResponse = client.get(url)
                if (response.status == HttpStatusCode.TooManyRequests) {
                    // Handle rate limiting explicitly
                    println("Received 429 Too Many Requests on attempt: ${attempt + 1} for: $accountId ")
                } else if (response.status.isSuccess()) {
                    val matchResponse: List<MatchHistoryDTO> = response.body()
                    if (matchResponse.isNotEmpty()) {
                        println("Got match for: $accountId")
                        return matchResponse
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

    /**
     * Lightweight account check used during signup. Returns the player's match
     * history when the account can be resolved, an empty list when the account
     * is valid but has no matches yet, or null when the account can't be
     * verified (not found, or a transient error after a quick retry).
     */
    suspend fun verifyAccount(accountId: String): List<MatchHistoryDTO>? {
        val url = "https://api.deadlock-api.com/v1/players/$accountId/match-history"
        val maxAttempts = 2

        repeat(maxAttempts) { attempt ->
            try {
                val response: HttpResponse = client.get(url)
                when {
                    response.status.isSuccess() -> return response.body()
                    response.status == HttpStatusCode.NotFound ||
                        response.status == HttpStatusCode.BadRequest -> {
                        // Account genuinely doesn't resolve — no point retrying.
                        println("Account $accountId not found (${response.status}).")
                        return null
                    }
                    response.status == HttpStatusCode.TooManyRequests ->
                        println("429 verifying $accountId on attempt ${attempt + 1}.")
                    else ->
                        println("Unexpected ${response.status} verifying $accountId.")
                }
            } catch (e: Exception) {
                println("Verify attempt ${attempt + 1} for $accountId failed: ${e.message}")
            }

            if (attempt < maxAttempts - 1) delay(3_000L)
        }
        return null
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