package api


import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
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
        val url = "https://api.deadlock-api.com/v1/players/$accountId/match-history"
        val matchResponse: List<MatchHistoryDTO> = client.get(url).body()
        return matchResponse[0]
    }

    suspend fun getMatchByMatchID(matchId: Long): MatchDTO?{
        val url = "https://api.deadlock-api.com/v1/matches/$matchId/metadata"
        return try {
            client.get(url).body<MatchDTO>()
        } catch (e: ClientRequestException) {
            // 4xx errors like 404
            if (e.response.status.value == 404) {
                println("Match $matchId not found — returning null")
                null
            } else {
                throw e
            }
        } catch (e: Exception) {
            println("Error fetching match $matchId: ${e.message}")
            null
        }
    }

    suspend fun close() {
        client.close()
    }
}