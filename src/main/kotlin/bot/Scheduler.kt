package bot

import api.DeadlockClient
import data.UserRepository
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import util.MatchMessageGenerator

suspend fun startScheduler(kord: Kord) = coroutineScope {
    launch {
        val client = DeadlockClient()
        while (true) {
            val users = UserRepository.getAllUsers()
            for ((discordId, accountId, discordUser, lastMatchId, channelId) in users) {
                try {
                    val latestMatch = client.getRecentMatch((accountId))
                    val channelIdSnowFlake: Snowflake = Snowflake(channelId!!)
                    if (latestMatch.matchId != lastMatchId?.toLong()) {
                        val additionalMatchInfo = client.getMatchByMatchID(latestMatch.matchId)
                        UserRepository.updateLastMatch(accountId, latestMatch.matchId.toString())
                        val channel = kord.getChannelOf<dev.kord.core.entity.channel.TextChannel>(channelIdSnowFlake)
                        MatchMessageGenerator.generate(latestMatch, discordUser, channel, additionalMatchInfo)
                    }
                    delay(1500L)
                } catch (e: Exception) {
                    println("Error checking matches for $accountId: ${e.message}")
                }
            }
            delay(10 * 60 * 1000L) // 3 minutes
        }
    }
}