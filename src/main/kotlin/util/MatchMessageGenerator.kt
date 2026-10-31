package util

import dev.kord.common.Color
import dev.kord.core.behavior.MessageBehavior
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.edit
import dev.kord.core.entity.Message
import dev.kord.rest.builder.message.EmbedBuilder
import models.HeroInfo

import models.HeroRepository
import models.MatchHistoryDTO
import java.util.Locale
import kotlin.time.Instant
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
        recentMatch: MatchHistoryDTO?,
        trackingNotice: String? = null,
    ): EmbedBuilder {
        val name = userName ?: "Player"

        return EmbedBuilder().apply {
            title = "🎉 Welcome, $name!"
            color = Color(0x1ABC9C)
            description = buildString {
                appendLine(trackingNotice ?: "You're all set up for automatic Deadlock match tracking.")
                appendLine()
                appendLine("**Linked account:** `$accountId`")
                if (recentMatch != null) {
                    val hero = HeroRepository.getHeroName(recentMatch.heroId)
                    val result = when {
                        recentMatch.matchResult !in 0..1 -> "⚪ Match completed"
                        recentMatch.matchResult == recentMatch.playerTeam -> "🟩 Win"
                        else -> "🟥 Loss"
                    }
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

    private fun heroInfo(id: Int) = HeroInfo(
        HeroRepository.getHeroName(id), HeroRepository.getHeroMinimapImage(id)
    )

    private fun rankImage(match: MatchHistoryDTO, details: MatchDTO?): String? =
        runCatching { RankRepository.getRankImage(match, details) }.getOrNull()
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

    private fun number(value: Int?): String = value?.let { String.format(Locale.US, "%,d", it) } ?: "—"

    private fun duration(match: MatchHistoryDTO): String {
        val seconds = match.matchDurationSeconds.coerceAtLeast(0)
        return "%d:%02d".format(Locale.US, seconds / 60, seconds % 60)
    }

    private fun result(match: MatchHistoryDTO): String = when {
        match.matchResult !in 0..1 || match.playerTeam !in 0..1 -> "⚔️ Result pending"
        match.matchResult == match.playerTeam -> "🏆 Victory"
        else -> "💀 Defeat"
    }

    fun buildRecentMatchEmbed(
        match: MatchHistoryDTO,
        userName: String?,
        additionalMatchInfo: MatchDTO?,
        heroLookup: (Int) -> HeroInfo = ::heroInfo,
        rankLookup: (MatchHistoryDTO, MatchDTO?) -> String? = ::rankImage
    ): EmbedBuilder {
        val hero = heroLookup(match.heroId)
        val finalStats = additionalMatchInfo?.matchInfo?.players
            ?.firstOrNull { it.accountId == match.accountId }?.finalStats
        val outcome = result(match)
        val kda = (match.kills + match.assists).toDouble() / match.deaths.coerceAtLeast(1)

        return EmbedBuilder().apply {
            title = "$outcome • ${(userName ?: "Player").take(180)}"
            description = "**${hero.name}** • Level ${match.heroLevel}\n" +
                listOfNotNull(MatchModes.label(match.gameMode, match.matchMode), "🕒 **${duration(match)}**", "<t:${match.startTime}:R>")
                    .joinToString(" • ")
            color = when {
                match.matchResult !in 0..1 || match.playerTeam !in 0..1 -> Color(0x95A5A6)
                match.matchResult == match.playerTeam -> Color(0xE5B454)
                else -> Color(0xC66574)
            }
            field {
                name = "⚔️ K / D / A"
                value = "**${match.kills} / ${match.deaths} / ${match.assists}**\n" +
                    if (match.deaths == 0) "Deathless" else "%.2f KDA".format(Locale.US, kda)
                inline = true
            }
            field {
                name = "💰 Souls"
                value = "**${number(match.netWorth)}**\n" +
                    if (match.matchDurationSeconds > 0) {
                        "${number((match.netWorth.toLong() * 60 / match.matchDurationSeconds).toInt())} / min"
                    } else "— / min"
                inline = true
            }
            field {
                name = "🎯 Farm"
                value = "**${number(match.lastHits)}** last hits\n**${number(match.denies)}** denies"
                inline = true
            }
            finalStats?.let { stats ->
                field {
                    name = "💥 Damage"
                    value = "**${number(stats.playerDamage)}** to players\n" +
                        "**${number(stats.objectiveDamage)}** to objectives"
                    inline = true
                }
                field {
                    name = "🛡️ Support & survival"
                    value = "**${number(stats.playerHealing)}** healing\n" +
                        "**${number(stats.damageAbsorbed)}** absorbed"
                    inline = true
                }
            }
            field {
                name = "📋 Full match"
                value = "Use **Open full match** below for both teams, heroes and final stats.\nMatch ID: `${match.matchId}`"
                inline = false
            }
            hero.minimapImage?.let { imageUrl -> thumbnail { url = imageUrl } }
            timestamp = Instant.fromEpochSeconds(match.startTime.toLong())
            footer {
                text = "Match ${match.matchId} • Deadlock"
                rankLookup(match, additionalMatchInfo)?.takeIf { it.isNotBlank() }?.let { icon = it }
            }
        }
    }

    suspend fun generateRecentMatch(
        match: MatchHistoryDTO,
        userName: String?,
        channel: MessageChannelBehavior,
        additionalMatchInfo: MatchDTO?
    ): Message = channel.createMessage {
        embeds = mutableListOf(buildRecentMatchEmbed(match, userName, additionalMatchInfo))
        matchReportButtons(listOf(match.matchId))
    }

    /** Replace a basic notification with the enriched one once full metadata arrives. */
    suspend fun updateRecentMatch(message: MessageBehavior, match: MatchHistoryDTO, userName: String?, additionalMatchInfo: MatchDTO) {
        message.edit {
            embeds = mutableListOf(buildRecentMatchEmbed(match, userName, additionalMatchInfo))
            matchReportButtons(listOf(match.matchId))
        }
    }

    fun buildLastFiveEmbed(
        matches: List<MatchHistoryDTO>,
        userName: String?,
        heroLookup: (Int) -> HeroInfo = ::heroInfo,
        notice: String? = null
    ): EmbedBuilder {
        val recent = matches.take(5)
        val wins = recent.count { it.matchResult in 0..1 && it.matchResult == it.playerTeam }
        val losses = recent.count { it.matchResult in 0..1 && it.playerTeam in 0..1 && it.matchResult != it.playerTeam }
        return EmbedBuilder().apply {
            title = "📊 ${(userName ?: "Player").take(180)}’s recent matches"
            color = Color(0x708EC8)
            description = if (recent.isEmpty()) {
                "No matches found yet. Your next game will appear here."
            } else {
                "**$wins W • $losses L** • ${recent.sumOf { it.kills }} kills • " +
                    "${recent.sumOf { it.assists }} assists"
            }
            notice?.let { description = "$it\n\n$description" }
            recent.forEach { match ->
                field {
                    name = listOfNotNull(result(match), heroLookup(match.heroId).name, MatchModes.label(match.gameMode, match.matchMode))
                        .joinToString(" • ")
                    value = "**${match.kills} / ${match.deaths} / ${match.assists}** K/D/A • " +
                        "💰 **${number(match.netWorth)}** souls • 🕒 ${duration(match)}\n" +
                        "<t:${match.startTime}:R> • Match ID: `${match.matchId}`"
                    inline = false
                }
            }
            footer { text = "Deadlock • Select a match button below to open its full report" }
        }
    }

    suspend fun generateLastFive(
        matches: List<MatchHistoryDTO>,
        userName: String?,
        channel: MessageChannelBehavior,
        notice: String? = null
    ) {
        channel.createMessage {
            embeds = mutableListOf(buildLastFiveEmbed(matches, userName, notice = notice))
            matchReportButtons(matches.take(5).map { it.matchId })
        }
    }
}
