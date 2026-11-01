package bot

import data.WeeklyDigestRepository
import data.UserRepository
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.channel.TextChannel
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import models.MatchHistoryDTO
import models.HeroRepository
import java.time.*
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** UTC Monday 00:00, half-open [start, end); only the most recently completed week is sent. */
internal fun completedWeek(now: Instant): Pair<Long, Long> {
    val end = now.atZone(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .atStartOfDay(ZoneOffset.UTC).toEpochSecond()
    return (end - 7 * 86400) to end
}

internal fun weeklyPlayerLine(name: String, matches: List<MatchHistoryDTO>, heroName: (Int) -> String, previous: List<MatchHistoryDTO> = emptyList()): String {
    val safe = name.replace("@", "＠").replace(Regex("[\\`*_~|<>\n\r]"), "").take(40)
    val rows = matches.distinctBy { it.matchId }.filter { it.matchResult in 0..1 && it.playerTeam in 0..1 && it.matchDurationSeconds > 0 }
    if (rows.isEmpty()) return "**$safe** · No completed matches observed."
    val wins = rows.count { it.matchResult == it.playerTeam }
    val hero = rows.groupingBy { it.heroId }.eachCount().entries.sortedWith(
        compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).first().key
    val spm = rows.sumOf { it.netWorth.toLong() } / (rows.sumOf { it.matchDurationSeconds.toLong() } / 60.0)
    val deaths = rows.sumOf { it.deaths.toLong() }.toDouble() / rows.size
    val old = previous.distinctBy { it.matchId }.filter { it.matchResult in 0..1 && it.playerTeam in 0..1 && it.matchDurationSeconds > 0 }
    val comparison = if (old.isEmpty()) "Previous week: no observed comparison sample" else {
        val oldSpm = old.sumOf { it.netWorth.toLong() } / (old.sumOf { it.matchDurationSeconds.toLong() } / 60.0)
        val winDelta = 100.0 * wins / rows.size - 100.0 * old.count { it.matchResult == it.playerTeam } / old.size
        "vs ${old.size} prior games: ${String.format(Locale.US, "%+.1f", winDelta)} win-rate points · ${String.format(Locale.US, "%+.0f", spm - oldSpm)} souls/min"
    }
    return "**$safe** · ${rows.size} games · ${wins}W/${rows.size - wins}L\n" +
        "Most played: ${heroName(hero).take(35)} · ${String.format(Locale.US, "%.0f", spm)} souls/min · ${String.format(Locale.US, "%.1f", deaths)} deaths/game\n" +
        "Weekly personal best: ${rows.maxOf { it.kills }} kills · $comparison"
}

internal suspend fun deliverWeekly(alreadySent: () -> Boolean, send: suspend () -> Unit, remember: () -> Unit) {
    if (alreadySent()) return
    send()
    remember() // Never suppress a retry when Discord rejected the post.
}

/** One message per channel, containing only opted-in current registrations. */
internal suspend fun sendWeeklyDigests(kord: Kord, users: List<UserRepository.UserRecord>, now: Instant = Instant.now()) {
    val (from, until) = completedWeek(now)
    val groups = withContext(Dispatchers.IO) { WeeklyDigestRepository.subscribers(users, eligibleBefore = until) }.groupBy { it.channelId!! }
    for ((channelId, members) in groups) {
        try {
            if (withContext(Dispatchers.IO) { WeeklyDigestRepository.delivered(channelId, from) }) continue
            val text = withContext(Dispatchers.IO) {
                val heading = "**Weekly Deadlock recap · ${Instant.ofEpochSecond(from).atZone(ZoneOffset.UTC).toLocalDate()}–${Instant.ofEpochSecond(until).atZone(ZoneOffset.UTC).toLocalDate()} UTC**\n"
                heading + members.sortedBy { it.accountId }.take(5).joinToString("\n\n") { user ->
                    weeklyPlayerLine(user.discordUser, WeeklyDigestRepository.matches(user.accountId, from, until), HeroRepository::getHeroName,
                        WeeklyDigestRepository.matches(user.accountId, from - 7 * 86400, from))
                } + (if (members.size > 5) "\n\n${members.size - 5} additional subscribers; use `/weekly action:preview` for your recap." else "") +
                    "\n\nObserved matches only; collection starts after opting in. Offline gaps or missing history can make this incomplete. Match start time determines its week."
            }
            val channel = kord.getChannelOf<TextChannel>(Snowflake(channelId)) ?: continue
            deliverWeekly(alreadySent = { false }, send = { channel.createMessage { content = text.take(2000) } },
                remember = { WeeklyDigestRepository.markDelivered(channelId, from) })
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { println("Weekly recap delivery failed for channel $channelId; retrying on a later cycle: ${e.message}") }
    }
}

/** Only a preview has anything to share; subscription changes stay between the caller and the bot. */
internal fun weeklyResponseIsPublic(action: String?, requested: Boolean) = requested && action?.lowercase() == "preview"

internal suspend fun handleWeekly(interaction: ChatInputCommandInteraction) {
    val response = interaction.deferWithVisibility(weeklyResponseIsPublic(interaction.command.strings["action"], interaction.requestedPublic()))
    try {
        val text = withContext(Dispatchers.IO) {
            val user = UserRepository.selectUserByDiscordID(interaction.user.id.toString())
                ?: return@withContext "Use `/signup` to link your account first."
            when (interaction.command.strings["action"]?.lowercase()) {
                "enable" -> {
                    if (user.channelId != interaction.channelId.toString()) return@withContext "Enable weekly recaps in your registered tracking channel."
                    WeeklyDigestRepository.enable(user)
                    "Weekly recaps enabled in your tracking channel. Posts run after Monday 00:00 UTC. Collection begins now; the first recap may be partial. Use `/weekly action:disable` to opt out."
                }
                "disable" -> { WeeklyDigestRepository.disable(user.discordId); "Weekly recaps disabled for your account." }
                "preview" -> {
                    val (from, until) = completedWeek(Instant.now())
                    "**Your last completed week (UTC)**\n" + weeklyPlayerLine(user.discordUser,
                        WeeklyDigestRepository.matches(user.accountId, from, until), HeroRepository::getHeroName,
                        WeeklyDigestRepository.matches(user.accountId, from - 7 * 86400, from)) +
                        "\n\nObserved matches only; collection starts after opting in and coverage may be incomplete."
                }
                else -> "Choose `/weekly action:enable`, `/weekly action:disable`, or `/weekly action:preview`."
            }
        }
        response.respond { content = text }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { response.respond { content = "Weekly recap settings are temporarily unavailable. Please try again shortly." } }
}
