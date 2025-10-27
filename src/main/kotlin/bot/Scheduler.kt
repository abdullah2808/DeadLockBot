package bot

import api.DeadlockClient
import data.UserRepository
import dev.kord.core.Kord
import dev.kord.rest.builder.message.create.embed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import util.Env

suspend fun startScheduler(kord: Kord) = coroutineScope {
    launch {
        while (true) {
            val users = UserRepository.getAllUsers()
            for ((discordId, accountId, lastMatchId) in users) {
                try {
                    val response = DeadlockClient.getPlayerMatches(accountId)
                    val latestMatch = response.matches.firstOrNull() ?: continue

                    if (latestMatch.match_id != lastMatchId) {
                        UserRepository.updateLastMatch(accountId, latestMatch.match_id)

                        val channel = kord.getChannelOf<dev.kord.core.entity.channel.TextChannel>(
                            Env.CHANNEL_ID
                        )
                        channel?.createMessage {
                            embed {
                                title = "🎮 New Match for $accountId"
                                description = """
                                    **Result:** ${latestMatch.result ?: "?"}
                                    **Map:** ${latestMatch.map ?: "?"}
                                    **Duration:** ${latestMatch.duration ?: 0}s
                                """.trimIndent()
                                color = if (latestMatch.result == "Win") 0x00FF00 else 0xFF0000
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("Error checking matches for $accountId: ${e.message}")
                }
            }
            delay(5 * 60 * 1000L) // 5 minutes
        }
    }
}