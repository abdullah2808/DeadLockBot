package api


import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import models.MatchResultDTO

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

    suspend fun getRecentMatch(accountId: String): MatchResultDTO {
        val url = "https://api.deadlock-api.com/v1/players/$accountId/match-history"
        val matchResponse: List<MatchResultDTO> = client.get(url).body()
        return matchResponse[0]
    }

    suspend fun close() {
        client.close()
    }
}