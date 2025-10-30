package util

import dev.kord.common.Color
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.entity.channel.TextChannel
import dev.kord.rest.builder.message.create.embed
import models.HeroRepository
import models.MatchResultDTO
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.datetime.Instant

object MatchMessageGenerator {
    suspend fun generate(match: MatchResultDTO, userName: String?, channel: TextChannel?) {
        val duration = match.matchDurationSeconds.seconds
        val minutes = duration.inWholeMinutes
        val seconds = duration.inWholeSeconds % 60
        val startTimeInstant = Instant.fromEpochSeconds(match.startTime.toLong())

        val hero = HeroRepository.getHeroName(match.heroId)
        val thumbnailImage = HeroRepository.getHeroMinimapImage(match.heroId)
        println(thumbnailImage)
        val resultEmoji : String
        val matchColor : Color

         when  {
             match.matchResult == match.playerTeam -> {
                resultEmoji = " **Won a Match!**  🏆🏆🏆"
                matchColor = Color(0x1ABC9C)
            }
             match.matchResult != match.playerTeam  -> {
                resultEmoji = " **Lost a Match!**  💀💀💀"
                matchColor = Color(0xfc473a)
            }
            else -> {
                resultEmoji = "⚔️ **Unknown Outcome**"
                matchColor = Color(0x1ABC9C)
            }
        }

        val messageDescription : String = buildString {
            appendLine("**Hero**: $hero")
            appendLine("**K/D/A**: ${match.kills}/${match.deaths}/${match.assists}")
            appendLine("**Souls:** ${match.netWorth}")
            appendLine("**Duration**: ${minutes}m ${seconds}s")
            appendLine()
        }

        channel?.createMessage {
            embed {
                title = "**${userName ?: "Player"}**" + resultEmoji
                description = messageDescription
                color = matchColor
                thumbnail {
                    url = thumbnailImage.toString()
                }
                timestamp = startTimeInstant
                footer {
                    text = "Match ID: ${match.matchId}"
                }
            }
        }
    }
}