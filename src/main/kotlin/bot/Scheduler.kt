package bot

import api.DeadlockClient
import data.UserRepository
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.rest.builder.message.create.embed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import util.MatchMessageFormatter

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
                        UserRepository.updateLastMatch(accountId, latestMatch.matchId.toString())

                    val message = MatchMessageFormatter.format(latestMatch, discordUser)
                    val channel = kord.getChannelOf<dev.kord.core.entity.channel.TextChannel>(
                        channelIdSnowFlake
                    )
                    channel?.createMessage(message)
                    }
                } catch (e: Exception) {
                    println("Error checking matches for $accountId: ${e.message}")
                }
            }
            delay(1 * 60 * 1000L) // 5 minutes
        }
    }
}