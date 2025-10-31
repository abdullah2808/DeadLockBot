package bot

import api.DeadlockClient
import data.UserRepository
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.channel.TextChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import util.MatchMessageGenerator

suspend fun startScheduler(kord: Kord) = coroutineScope {
    val client = DeadlockClient()

    while (true) {
        val users = UserRepository.getAllUsers()

        // Launch concurrent requests for all users
        val jobs = users.map { (discordId, accountId, discordUser, lastMatchId, channelId) ->
            launch {
                try {
                    println("Getting recent match for: $accountId")

                    val latestMatch = client.getRecentMatch(accountId)
                    val channelIdSnowflake = Snowflake(channelId!!)

                    if (latestMatch.matchId != lastMatchId?.toLong()) {
                        val additionalMatchInfo = client.getMatchByMatchID(latestMatch.matchId)
                        UserRepository.updateLastMatch(accountId, latestMatch.matchId.toString())
                        val channel = kord.getChannelOf<TextChannel>(channelIdSnowflake)
                        MatchMessageGenerator.generate(latestMatch, discordUser, channel, additionalMatchInfo)
                    }

                    // Small stagger between launches to avoid overwhelming the API
                    delay(6000L)

                } catch (e: Exception) {
                    println("Error checking matches for $accountId: ${e.message}")
                }
            }
        }

        // Wait for all user jobs to finish before next cycle
        jobs.joinAll()

        println("All users processed. Waiting before next cycle...")
        delay(3 * 60 * 1000L) // 3 minutes
    }
}
