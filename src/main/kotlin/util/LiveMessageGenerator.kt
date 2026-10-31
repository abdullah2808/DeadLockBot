package util

import api.asDiscordTitleText
import api.gc.ProfileCard
import bot.ActiveBoard
import bot.ListedMatch
import bot.RankChange
import bot.SeatedPlayer
import dev.kord.common.Color
import dev.kord.rest.builder.message.EmbedBuilder
import models.ActiveMatchDTO
import models.HeroRepository
import models.RankBadge
import java.text.NumberFormat
import java.util.Locale

/** Embeds for `/profile`, `/live`, `/active` and match alerts. Hero names are injectable so tests stay offline. */
object LiveMessageGenerator {
    private val numbers = NumberFormat.getIntegerInstance(Locale.US)
    private fun Long.formatted() = numbers.format(this)

    fun profileEmbed(
        playerName: String,
        card: ProfileCard,
        rank: RankBadge?,
        heroName: (Int) -> String = HeroRepository::getHeroName,
    ) = EmbedBuilder().apply {
        title = "$playerName • Deadlock profile"
        color = Color(0x9B59B6)
        rank?.image?.let { thumbnail { url = it } }
        description = buildString {
            append("**Rank:** ")
            appendLine(when {
                card.rankedBadgeLevel == null -> "Unranked or hidden"
                rank != null -> rank.label
                else -> "Badge ${card.rankedBadgeLevel}"
            })
            val stats = listOfNotNull(
                card.gamesPlayed?.let { "**Games:** ${it.formatted()}" },
                card.wins?.let { "**Wins:** ${it.formatted()}" },
                card.kills?.let { "**Kills:** ${it.formatted()}" },
                winRate(card)?.let { "**Win rate:** $it" },
            )
            if (stats.isNotEmpty()) appendLine(stats.joinToString(" · "))
        }
        if (card.heroes.isNotEmpty()) field {
            name = "Featured heroes"
            value = card.heroes.joinToString("\n") {
                "**${heroName(it.heroId)}** — ${it.wins.formatted()} wins · ${it.kills.formatted()} kills"
            }
        }
        footer { text = "Account ${card.accountId} · Profile card from Valve's Game Coordinator" }
    }

    private fun winRate(card: ProfileCard): String? {
        val games = card.gamesPlayed?.takeIf { it > 0 } ?: return null
        val wins = card.wins ?: return null
        return "%.1f%%".format(Locale.US, wins * 100.0 / games)
    }

    fun liveEmbed(
        playerName: String,
        accountId: Long,
        match: ActiveMatchDTO,
        trackedNames: Map<Long, String> = emptyMap(),
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
        heroName: (Int) -> String = HeroRepository::getHeroName,
    ) = EmbedBuilder().apply {
        val player = match.players.firstOrNull { it.accountId == accountId }
        title = "🔴 $playerName is live — Match ${match.matchId}"
        color = Color(0xE74C3C)
        description = buildString {
            player?.let { appendLine("Playing **${heroName(it.heroId)}** for **${teamName(it.team)}**") }
            val details = listOfNotNull(
                MatchModes.label(match.gameMode, match.matchMode),
                elapsedSeconds(match, nowEpochSeconds)?.let { "⏱ ${formatDuration(it)}" },
                match.spectators.takeIf { it > 0 }?.let { "👁 ${it.formatted()} watching" },
            )
            if (details.isNotEmpty()) appendLine(details.joinToString(" · "))
            if (match.netWorthTeam0 > 0 || match.netWorthTeam1 > 0) {
                val lead = match.netWorthTeam0 - match.netWorthTeam1
                val leader = if (lead >= 0) teamName(0) else teamName(1)
                append("**Souls:** ${teamName(0)} ${match.netWorthTeam0.formatted()} · ${teamName(1)} ${match.netWorthTeam1.formatted()}")
                appendLine(if (lead != 0L) " ($leader +${kotlin.math.abs(lead).formatted()})" else "")
            }
        }
        for (team in 0..1) {
            val roster = match.players.filter { it.team == team }
            if (roster.isEmpty()) continue
            field {
                name = teamName(team)
                inline = true
                value = roster.joinToString("\n") { p ->
                    val tracked = trackedNames[p.accountId]?.let { " — **$it**" } ?: ""
                    val abandoned = if (p.abandoned == true) " (left)" else ""
                    "${heroName(p.heroId)}$tracked$abandoned"
                }
            }
        }
        footer { text = "Use /match ${match.matchId} once the match ends for the full scoreboard." }
    }

    /** [listFromGc] names the live list's source, and is null when no source could be reached. */
    internal fun activeEmbed(
        board: ActiveBoard,
        listFromGc: Boolean?,
        steamBotConfigured: Boolean,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
        heroName: (Int) -> String = HeroRepository::getHeroName,
    ) = EmbedBuilder().apply {
        title = "Tracked players in this channel"
        color = Color(0xE74C3C)
        val lines = buildList {
            if (listFromGc == null) {
                add("⚠️ The live match list is unavailable right now, so match details can't be shown.")
                add("")
            }
            if (board.listed.isNotEmpty()) add("**In a listed match**")
            board.listed.forEach { listed ->
                add(activeMatchDetails(listed, nowEpochSeconds))
                listed.players.forEach { add(seatLine(it, heroName)) }
                add("")
            }
            if (board.inDeadlock.isNotEmpty()) {
                add("**In Deadlock, match unknown**")
                add("Steam shows they have the game open, which doesn't prove they're in a match.")
                board.inDeadlock.forEach { add("• **${displayName(it.name)}**") }
                add("")
            }
            if (board.notSeen.isNotEmpty()) {
                add("**Not seen**")
                add("They may still be playing, because the live list features only some games.")
                board.notSeen.forEach { player ->
                    val reason = if (player.inDeadlock == false) "Steam doesn't show them in Deadlock" else "the bot can't see their Steam status"
                    add("• **${displayName(player.name)}**: $reason")
                }
            }
        }
        description = fitLines(lines.dropLastWhile { it.isEmpty() })
        val source = when (listFromGc) {
            true -> "Valve's Watch list"
            false -> "deadlock-api's copy of Valve's Watch list"
            null -> null
        }
        val tip = if (steamBotConfigured && board.notSeen.any { it.inDeadlock == null }) "Steam friends of the bot show their Steam status: /steambot." else null
        footer { text = listOfNotNull(source?.let { "Matches from $it, refreshed at most once a minute." }, tip).joinToString(" ").ifEmpty { "Steam status only." } }
    }

    /** Elapsed time goes stale in a posted message, so this uses a Discord timestamp that keeps counting. */
    internal fun matchAlertEmbed(listed: ListedMatch, heroName: (Int) -> String = HeroRepository::getHeroName) = EmbedBuilder().apply {
        val names = listed.players.map { displayName(it.player.name.take(32)) }
        val subject = when (names.size) {
            1 -> "${names[0]} is"
            2 -> "${names[0]} and ${names[1]} are"
            3 -> "${names[0]}, ${names[1]} and ${names[2]} are"
            else -> "${names[0]}, ${names[1]} and ${names.size - 2} others are"
        }
        title = "🎮 $subject in a match"
        color = Color(0xE74C3C)
        val match = listed.match
        description = fitLines(listed.players.map { seatLine(it, heroName) } + listOfNotNull(
            MatchModes.label(match.gameMode, match.matchMode),
            if (match.startTime > 0) "Started <t:${match.startTime}:R>" else match.durationS?.takeIf { it > 0 }?.let { "⏱ ${formatDuration(it)} when posted" },
            "Match `${match.matchId}`",
        ).joinToString(" · "))
        footer { text = "Only matches on Valve's Watch list are detected, so other matches never alert. /matchalerts action:disable turns these off." }
    }

    private fun activeMatchDetails(listed: ListedMatch, nowEpochSeconds: Long): String {
        val match = listed.match
        return listOfNotNull(
            MatchModes.label(match.gameMode, match.matchMode),
            elapsedSeconds(match, nowEpochSeconds)?.let { "⏱ ${formatDuration(it)}" },
            if (match.netWorthTeam0 > 0 || match.netWorthTeam1 > 0)
                "Souls: ${teamName(0)} ${match.netWorthTeam0.formatted()}, ${teamName(1)} ${match.netWorthTeam1.formatted()}" else null,
            "Match `${match.matchId}`",
        ).joinToString(" · ")
    }

    private fun seatLine(seated: SeatedPlayer, heroName: (Int) -> String): String {
        val left = if (seated.seat.abandoned == true) " (left)" else ""
        return "• **${displayName(seated.player.name)}**: ${heroName(seated.seat.heroId)} for ${teamName(seated.seat.team)}$left"
    }

    private fun displayName(name: String) = name.take(40).asDiscordTitleText()

    /** Keeps within Discord's description limit however many players a channel tracks. */
    private fun fitLines(lines: List<String>, limit: Int = 4000): String {
        val kept = mutableListOf<String>()
        var used = 0
        for (line in lines) {
            if (used + line.length + 1 > limit) return (kept + "…and ${lines.size - kept.size} more lines.").joinToString("\n")
            kept += line
            used += line.length + 1
        }
        return kept.joinToString("\n")
    }

    fun rankChangeEmbed(playerName: String, change: RankChange, previous: RankBadge?, current: RankBadge?) = EmbedBuilder().apply {
        val name = playerName.take(180)
        val currentLabel = current?.label ?: "Badge ${change.current}"
        val previousLabel = previous?.label ?: change.previous?.let { "Badge $it" }
        when (change.direction) {
            RankChange.Direction.UP -> {
                title = "📈 $name ranked up!"
                color = Color(0x2ECC71)
            }
            RankChange.Direction.DOWN -> {
                title = "📉 $name's rank changed"
                color = Color(0x95A5A6)
            }
            RankChange.Direction.PLACED -> {
                title = "🎖️ $name earned a rank!"
                color = Color(0xF1C40F)
            }
        }
        description = previousLabel?.let { "$it → **$currentLabel**" } ?: "Now **$currentLabel**"
        current?.image?.let { thumbnail { url = it } }
        footer { text = "Deadlock rank · from the player's profile card" }
    }

    internal fun teamName(team: Int) = when (team) {
        0 -> "Amber"
        1 -> "Sapphire"
        else -> "Spectators"
    }

    /** The GC's duration lags; derive it from the start time when that is available. */
    internal fun elapsedSeconds(match: ActiveMatchDTO, nowEpochSeconds: Long): Long? = when {
        match.startTime > 0 && nowEpochSeconds >= match.startTime -> nowEpochSeconds - match.startTime
        else -> match.durationS?.takeIf { it > 0 }
    }

    private fun formatDuration(seconds: Long) = "%d:%02d".format(seconds / 60, seconds % 60)
}
