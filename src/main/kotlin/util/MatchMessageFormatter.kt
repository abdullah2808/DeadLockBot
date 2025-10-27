package util

import models.HeroRepository
import models.MatchResultDTO
import kotlin.time.Duration.Companion.seconds

object MatchFormatter {
    fun format(match: MatchResultDTO): String {
        val duration = match.matchDurationSeconds.seconds
        val minutes = duration.inWholeMinutes
        val seconds = duration.inWholeSeconds % 60

        val hero = HeroRepository.getHeroName(match.heroId)
        val resultEmoji = when (match.matchResult) {
            1 -> "🏆 **Victory!**"
            0 -> "💀 **Defeat**"
            else -> "⚔️ **Unknown Outcome**"
        }

        return buildString {
            appendLine("🎮 **${match.username ?: "Player"}** finished a match!")
            appendLine("Hero: **$hero** (Lv ${match.heroLevel})")
            appendLine("K/D/A: **${match.kills}/${match.deaths}/${match.assists}**")
            appendLine("Net Worth: **${match.netWorth}**")
            appendLine("Duration: **${minutes}m ${seconds}s**")
            appendLine("Result: $resultEmoji")
            appendLine("Match ID: `${match.matchId}`")
        }
    }
}