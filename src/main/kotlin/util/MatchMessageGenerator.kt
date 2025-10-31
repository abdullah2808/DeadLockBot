package util

import dev.kord.common.Color
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.entity.channel.TextChannel
import dev.kord.rest.builder.message.create.embed
import models.HeroRepository
import models.MatchHistoryDTO
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant
import models.MatchDTO
import models.RankRepository

object MatchMessageGenerator {
    suspend fun generate(
        match: MatchHistoryDTO,
        userName: String?,
        channel: TextChannel?,
        additionalMatchInfo: MatchDTO? )
    {
        // Time
        val duration = match.matchDurationSeconds.seconds
        val minutes = duration.inWholeMinutes
        val seconds = duration.inWholeSeconds % 60
        val startTimeInstant = Instant.fromEpochSeconds(match.startTime.toLong())

        // Hero repo info
        val hero = HeroRepository.getHeroName(match.heroId)
        val thumbnailImage = HeroRepository.getHeroMinimapImage(match.heroId)

        // Rank repo info
        val rankImage = RankRepository.getRankImage(match, additionalMatchInfo)

        // Damage Info
        val playerStats = additionalMatchInfo?.matchInfo?.players
            ?.firstOrNull { it.accountId == match.accountId }
            ?.stats
        val playerDamage = playerStats?.get(playerStats.size - 1)?.playerDamage
        val objectiveDamage = playerStats?.get(playerStats.size - 1)?.objectiveDamage

        // Result processing and message generation
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
            playerDamage?.let {
                appendLine("**Player Damage**: $playerDamage")
            }
            objectiveDamage?.let {
                appendLine("**Objective Damage**: $objectiveDamage")
            }
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
                    icon = rankImage
                    text = "Match ID: ${match.matchId}"
                }
            }
        }
    }
}