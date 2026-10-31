package bot

import data.CachedPlayerMatchTable
import data.MatchCacheRepository
import data.UserRepository
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.core.behavior.interaction.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import models.MatchDTO
import models.MatchHistoryDTO
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.Locale

internal data class TrendSample(val summary: MatchHistoryDTO, val metadata: MatchDTO? = null)

internal fun trendReport(input: List<TrendSample>): String {
    val samples = input.distinctBy { it.summary.matchId }.sortedWith(
        compareByDescending<TrendSample> { it.summary.startTime }.thenByDescending { it.summary.matchId })
        .filter { it.summary.matchDurationSeconds > 0 && it.summary.playerTeam in 0..1 && it.summary.matchResult in 0..1 }
        .take(40)
    if (samples.isEmpty()) return "No completed tracked matches yet. Check back after the tracker has collected matches."
    val recent = samples.take(20)
    val previous = samples.drop(20)
    fun format(value: Double) = String.format(Locale.US, "%.1f", value)
    fun metrics(rows: List<TrendSample>): List<Pair<String, Double?>> {
        val minutes = rows.sumOf { it.summary.matchDurationSeconds.toLong() } / 60.0
        val damage = rows.mapNotNull { sample -> sample.metadata?.matchInfo?.players
            ?.firstOrNull { it.accountId == sample.summary.accountId }?.finalStats?.playerDamage
            ?.takeIf { it >= 0 }?.let { it.toLong() to sample.summary.matchDurationSeconds } }
        return listOf(
            "Win rate (%)" to (100.0 * rows.count { it.summary.matchResult == it.summary.playerTeam } / rows.size),
            "Souls / min" to (rows.sumOf { it.summary.netWorth.toLong() } / minutes),
            "Deaths / match" to (rows.sumOf { it.summary.deaths.toLong() }.toDouble() / rows.size),
            "Damage / min (${damage.size}/${rows.size} matches)" to damage.takeIf { it.isNotEmpty() }
                ?.let { it.sumOf { p -> p.first } / (it.sumOf { p -> p.second.toLong() } / 60.0) },
        )
    }
    val old = previous.takeIf { it.isNotEmpty() }?.let(::metrics)
    return buildString {
        append("**Your performance trends**\nLatest ${recent.size} vs previous ${previous.size} completed matches\n")
        metrics(recent).forEachIndexed { index, (name, value) ->
            val before = old?.get(index)?.second
            append("\n$name: ${value?.let(::format) ?: "Unavailable"}")
            if (value != null && before != null) append(" · previous ${format(before)} · Δ ${format(value - before)}")
        }
        append("\n\nAll modes and heroes combined. Rates use total recorded match minutes. Damage uses only matches with damage data.")
        if (samples.size < 40) append("\nCollecting history: ${samples.size}/40 matches available; the comparison may be partial.")
        append("\nRetained tracker history only; older matches are not backfilled and invalid results are excluded.")
    }
}

internal suspend fun handleTrends(interaction: ChatInputCommandInteraction) {
    val response = interaction.deferEphemeralResponse()
    try {
        val text = withContext(Dispatchers.IO) {
            val user = UserRepository.selectUserByDiscordID(interaction.user.id.toString())
                ?: return@withContext "Use `/signup` to link your account first."
            val rows = transaction {
                CachedPlayerMatchTable.selectAll().where { CachedPlayerMatchTable.accountId eq user.accountId }
                    .orderBy(CachedPlayerMatchTable.matchId to SortOrder.DESC).limit(40).mapNotNull { row ->
                        val id = row[CachedPlayerMatchTable.matchId]
                        MatchCacheRepository.find(user.accountId, id)?.let { TrendSample(it, MatchCacheRepository.findMetadata(id)) }
                    }
            }
            trendReport(rows)
        }
        response.respond { content = text }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) { response.respond { content = "Your tracked trends are temporarily unavailable. Please try again shortly." } }
}
