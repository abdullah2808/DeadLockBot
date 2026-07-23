package util

import dev.kord.common.Color
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.entity.channel.TextChannel
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.create.embed
import models.HeroRepository
import models.MatchHistoryDTO
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant
import models.MatchDTO
import models.RankRepository

object MatchMessageGenerator {

    /**
     * Builds the embed shown when a player signs up. Confirms what was linked,
     * previews their most recent match when available, and explains what the
     * bot will do next. Returned as an [EmbedBuilder] so it can be attached to
     * the slash-command interaction response.
     */
    fun buildWelcomeEmbed(
        userName: String?,
        accountId: String,
        recentMatch: MatchHistoryDTO?
    ): EmbedBuilder {
        val name = userName ?: "Player"

        return EmbedBuilder().apply {
            title = "🎉 Welcome, $name!"
            color = Color(0x1ABC9C)
            description = buildString {
                appendLine("You're all set up for automatic Deadlock match tracking.")
                appendLine()
                appendLine("**Linked account:** `$accountId`")
                if (recentMatch != null) {
                    val hero = HeroRepository.getHeroName(recentMatch.heroId)
                    val result = if (recentMatch.matchResult == recentMatch.playerTeam) "🟩 Win" else "🟥 Loss"
                    appendLine(
                        "**Most recent match:** $result as **$hero** " +
                            "(${recentMatch.kills}/${recentMatch.deaths}/${recentMatch.assists})"
                    )
                } else {
                    appendLine("_No matches found yet — I'll post your next one automatically._")
                }
                appendLine()
                appendLine("**What happens next**")
                appendLine("• I'll post each new match in this channel as you play.")
                appendLine("• Use `/lastfive` to review your recent games.")
                appendLine("• Use `/unsubscribe` to stop tracking anytime.")
            }
            recentMatch?.let { match ->
                HeroRepository.getHeroMinimapImage(match.heroId)?.let { imageUrl ->
                    thumbnail { url = imageUrl }
                }
            }
            footer { text = "Deadlock match tracking" }
        }
    }

    suspend fun generateRecentMatch(
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
                thumbnailImage?.let { imageUrl ->
                    thumbnail {
                        url = imageUrl
                    }
                }
                timestamp = startTimeInstant
                footer {
                    icon = rankImage
                    text = "Match ID: ${match.matchId}"
                }
            }
        }
    }

    suspend fun generateLastFive(
        matches: List<MatchHistoryDTO>,
        userName: String?,
        channel: TextChannel?, ) {
        val topMatches = matches.take(5)

        val messageDescription : String = buildString {
            for (match in topMatches) {
                val hero = HeroRepository.getHeroName(match.heroId)

                val color = if (match.matchResult == match.playerTeam) "🟩" else "🟥"

                val duration = match.matchDurationSeconds.seconds
                val minutes = duration.inWholeMinutes
                val seconds = duration.inWholeSeconds % 60


                appendLine(
                    "$color **$hero** — ${match.kills}/${match.deaths}/${match.assists}  |  " +
                            "💰 ${match.netWorth}  |  🕒 ${minutes}m ${seconds}s"
                )
            }
        }

        channel?.createMessage {
            embed {
                title = "**${userName ?: "Player"}’s Last 5 Matches**"
                description = messageDescription
                color = Color(0x3498db)
            }
        }
    }
}