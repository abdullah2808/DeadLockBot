package api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import util.Env

object DeadlockClient {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        defaultRequest {
            header("Authorization", "Bearer ${Env.DEADLOCK_API_KEY}")
        }
    }

    suspend fun getPlayerMatches(accountId: String): PlayerMatchesResponse =
        client.get("${Env.DEADLOCK_BASE_URL}/players/$accountId/matches").body()
}