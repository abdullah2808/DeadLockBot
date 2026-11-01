package bot

import api.DeadlockClient
import api.PlayerNameService
import api.asDiscordTitleText
import api.gc.MatchHistoryProvider
import data.UserRepository
import dev.kord.common.Color
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.rest.builder.message.EmbedBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import models.HeroRepository
import models.MatchHistoryDTO
import models.Stats
import util.MatchModes
import java.util.Locale

internal const val DEFAULT_COMPARE_MATCHES = 20

/** Souls and damage rates differ too much between modes to compare fairly, so callers can narrow the sample. */
internal enum class CompareMode(val option: String, val title: String) {
    ALL("all", "All modes"), RANKED("ranked", "Ranked"), STANDARD("standard", "Standard"), STREET_BRAWL("street_brawl", "Street Brawl");

    fun includes(match: MatchHistoryDTO) = when (this) {
        ALL -> true
        RANKED -> MatchModes.affectsRank(match.gameMode, match.matchMode)
        STANDARD -> !MatchModes.isStreetBrawl(match.gameMode) && match.matchMode == MatchModes.MATCH_MODE_STANDARD
        STREET_BRAWL -> MatchModes.isStreetBrawl(match.gameMode)
    }

    companion object { fun fromOption(value: String?) = entries.firstOrNull { it.option == value } ?: ALL }
}

internal data class CompareStats(
    val games: Int, val wins: Int, val kills: Double, val deaths: Double, val assists: Double, val kda: Double,
    val soulsPerMin: Double, val lastHitsPerMin: Double, val deniesPerMin: Double,
    val damagePerMin: Double?, val healingPerMin: Double?, val damageCoverage: Int,
    val topHero: Int, val topHeroGames: Int,
) { val winRate get() = 100.0 * wins / games }

internal data class HeadToHead(val togetherWins: Int, val togetherLosses: Int, val leftWins: Int, val rightWins: Int) {
    val shared get() = togetherWins + togetherLosses + leftWins + rightWins
}

/** Completed matches for the mode, newest first; the same validity rule as `/trends`. */
internal fun completedSamples(samples: List<TrendSample>, mode: CompareMode): List<TrendSample> =
    samples.distinctBy { it.summary.matchId }
        .filter { it.summary.matchDurationSeconds > 0 && it.summary.playerTeam in 0..1 && it.summary.matchResult in 0..1 && mode.includes(it.summary) }
        .sortedWith(compareByDescending<TrendSample> { it.summary.startTime }.thenByDescending { it.summary.matchId })

internal fun compareStats(samples: List<TrendSample>): CompareStats? {
    if (samples.isEmpty()) return null
    val rows = samples.map { it.summary }
    val games = rows.size
    val minutes = rows.sumOf { it.matchDurationSeconds.toLong() } / 60.0
    val deaths = rows.sumOf { it.deaths.toLong() }
    /** Only matches recording the stat count, so missing metadata never lowers a rate. */
    fun statRate(stat: (Stats) -> Int?): Pair<Double?, Int> {
        val known = samples.mapNotNull { sample ->
            val player = sample.metadata?.matchInfo?.players?.firstOrNull { it.accountId == sample.summary.accountId }
            player?.finalStats?.let(stat)?.takeIf { it >= 0 }?.let { it.toLong() to sample.summary.matchDurationSeconds.toLong() }
        }
        return known.takeIf { it.isNotEmpty() }?.let { it.sumOf { p -> p.first } / (it.sumOf { p -> p.second } / 60.0) } to known.size
    }
    val (damage, coverage) = statRate { it.playerDamage }
    val hero = rows.groupingBy { it.heroId }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).first()
    return CompareStats(
        games = games, wins = rows.count { it.matchResult == it.playerTeam },
        kills = rows.sumOf { it.kills.toLong() }.toDouble() / games, deaths = deaths.toDouble() / games,
        assists = rows.sumOf { it.assists.toLong() }.toDouble() / games,
        kda = rows.sumOf { it.kills.toLong() + it.assists.toLong() }.toDouble() / deaths.coerceAtLeast(1),
        soulsPerMin = rows.sumOf { it.netWorth.toLong() } / minutes,
        lastHitsPerMin = rows.sumOf { it.lastHits.toLong() } / minutes,
        deniesPerMin = rows.sumOf { it.denies.toLong() } / minutes,
        damagePerMin = damage, healingPerMin = statRate { it.playerHealing }.first, damageCoverage = coverage,
        topHero = hero.key, topHeroGames = hero.value,
    )
}

/** Inconsistent results are skipped, as in `/party`; a shared team does not prove they queued together. */
internal fun headToHead(left: List<MatchHistoryDTO>, right: List<MatchHistoryDTO>): HeadToHead {
    val others = right.associateBy { it.matchId }
    var together = 0; var togetherLost = 0; var leftWins = 0; var rightWins = 0
    for (match in left.distinctBy { it.matchId }) {
        val other = others[match.matchId]?.takeIf { it.matchResult == match.matchResult } ?: continue
        val leftWon = match.matchResult == match.playerTeam
        when {
            match.playerTeam == other.playerTeam -> if (leftWon) together++ else togetherLost++
            leftWon -> leftWins++
            else -> rightWins++
        }
    }
    return HeadToHead(together, togetherLost, leftWins, rightWins)
}

private fun Double.fixed(decimals: Int) = String.format(Locale.US, "%.${decimals}f", this)

/** Names are literal here: a backtick would end the block and wide characters would only misalign it. */
private fun tableName(name: String) = name.replace(Regex("[`\\p{Cc}]"), "").trim().take(9).ifBlank { "Player" }

/** A star needs a visible difference, so values that print the same are never called better. */
internal fun compareTable(leftName: String, rightName: String, left: CompareStats, right: CompareStats): String {
    fun row(label: String, a: Double?, b: Double?, decimals: Int = 1, suffix: String = "", lowerIsBetter: Boolean = false): String {
        val first = a?.fixed(decimals); val second = b?.fixed(decimals)
        val leftBetter = a != null && b != null && first != second && (if (lowerIsBetter) a < b else a > b)
        val rightBetter = a != null && b != null && first != second && !leftBetter
        fun cell(text: String?, better: Boolean) = (text?.plus(suffix) ?: "—") + if (better) "*" else " "
        return "%-13s%10s%10s".format(Locale.US, label, cell(first, leftBetter), cell(second, rightBetter))
    }
    return listOf(
        "%-13s%10s%10s".format(Locale.US, "", tableName(leftName) + " ", tableName(rightName) + " "),
        "%-13s%10s%10s".format(Locale.US, "Games", "${left.games} ", "${right.games} "),
        row("Win rate", left.winRate, right.winRate, suffix = "%"),
        row("KDA ratio", left.kda, right.kda, decimals = 2),
        row("Kills", left.kills, right.kills),
        row("Deaths", left.deaths, right.deaths, lowerIsBetter = true),
        row("Assists", left.assists, right.assists),
        row("Souls/min", left.soulsPerMin, right.soulsPerMin, decimals = 0),
        row("Last hits/min", left.lastHitsPerMin, right.lastHitsPerMin),
        row("Denies/min", left.deniesPerMin, right.deniesPerMin, decimals = 2),
        row("Damage/min", left.damagePerMin, right.damagePerMin, decimals = 0),
        row("Healing/min", left.healingPerMin, right.healingPerMin, decimals = 0),
    ).joinToString("\n")
}

internal fun compareEmbed(
    leftName: String, rightName: String, left: CompareStats, right: CompareStats, record: HeadToHead,
    mode: CompareMode, requested: Int, heroName: (Int) -> String = HeroRepository::getHeroName,
) = EmbedBuilder().apply {
    val shownLeft = leftName.take(40).asDiscordTitleText()
    val shownRight = rightName.take(40).asDiscordTitleText()
    title = "$shownLeft vs $shownRight"
    color = Color(0x3498DB)
    description = "Latest $requested completed matches · ${mode.title}\n```\n${compareTable(leftName, rightName, left, right)}\n```"
    field {
        name = "Most played"
        value = "$shownLeft: ${heroName(left.topHero).take(30)} (${left.topHeroGames})\n$shownRight: ${heroName(right.topHero).take(30)} (${right.topHeroGames})"
    }
    field {
        name = "Shared matches"
        value = if (record.shared == 0) "None found in the available history." else
            "Same team: ${record.togetherWins}W/${record.togetherLosses}L\nOpposing teams: $shownLeft ${record.leftWins} – ${record.rightWins} $shownRight"
    }
    val notes = buildList {
        add("* better value")
        if (left.games < requested || right.games < requested) add("fewer matches available than requested")
        if (left.damageCoverage < left.games || right.damageCoverage < right.games)
            add("damage from ${left.damageCoverage}/${left.games} and ${right.damageCoverage}/${right.games} matches")
        add("descriptive only, not a skill rating")
    }
    footer { text = notes.joinToString(" · ") }
}

/** Tracked rows are kept fresh by the scheduler; other accounts' stored rows may be stale, so they are only a fallback. */
internal suspend fun loadCompareSamples(
    tracked: Boolean, stored: suspend () -> List<TrendSample>, live: suspend () -> List<MatchHistoryDTO>,
): List<TrendSample> {
    val cached = stored()
    if (tracked && cached.isNotEmpty()) return cached
    val fresh = try { live() }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { println("Compare history lookup failed: ${e.message}"); emptyList() }
    val metadata = cached.associate { it.summary.matchId to it.metadata }
    return fresh.map { TrendSample(it, metadata[it.matchId]) }.ifEmpty { cached }
}

private suspend fun liveHistory(account: String, gc: MatchHistoryProvider?): List<MatchHistoryDTO> {
    if (gc != null) {
        try { gc.recentHistory(account).summaries.takeIf { it.isNotEmpty() }?.let { return it } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { println("GC history unavailable for compare of $account: ${e.message}") }
    }
    val client = DeadlockClient()
    try { return client.getRecentMatch(account) } finally { client.close() }
}

private data class CompareSubject(val account: String, val name: String, val tracked: Boolean)

internal suspend fun handleCompare(interaction: ChatInputCommandInteraction, gc: MatchHistoryProvider?, playerNames: PlayerNameService) {
    val response = interaction.deferWithVisibility()
    try {
        val users = withContext(Dispatchers.IO) { UserRepository.getAllUsers() }
        fun linked(id: String) = users.firstOrNull { it.discordId == id }
        val options = interaction.command
        val otherUser = options.users["player"]
        val rawAccount = options.strings["account_id"]
        if ((otherUser == null) == (rawAccount == null)) {
            response.respond { content = "Choose one player to compare: set either `player` (a signed-up Discord user) or `account_id`." }
            return
        }
        val right = if (otherUser != null) {
            val user = linked(otherUser.id.toString())
            if (user == null) {
                response.respond { content = "That Discord user hasn't used `/signup`. Use `account_id` to compare their Deadlock account instead." }
                return
            }
            CompareSubject(user.accountId, user.discordUser, true)
        } else {
            val account = resolveAccountInput(rawAccount!!) { message -> response.respond { content = message } } ?: return
            val user = users.firstOrNull { it.accountId == account }
            CompareSubject(account, user?.discordUser ?: playerNames.nameFor(account), user != null)
        }
        val againstUser = options.users["against"]
        val leftUser = linked((againstUser?.id ?: interaction.user.id).toString())
        if (leftUser == null) {
            response.respond {
                content = if (againstUser != null) "The `against` user hasn't used `/signup`."
                else "Use `/signup` to compare against your own account, or set `against` to a signed-up Discord user."
            }
            return
        }
        val left = CompareSubject(leftUser.accountId, leftUser.discordUser, true)
        if (left.account == right.account) {
            response.respond { content = "Pick two different players to compare." }
            return
        }
        val mode = CompareMode.fromOption(options.strings["mode"])
        val requested = options.integers["matches"]?.toInt()?.coerceIn(5, 40) ?: DEFAULT_COMPARE_MATCHES
        suspend fun samples(subject: CompareSubject) = completedSamples(loadCompareSamples(subject.tracked,
            { withContext(Dispatchers.IO) { storedTrendSamples(subject.account) } }, { liveHistory(subject.account, gc) }), mode)
        val leftSamples = samples(left)
        val rightSamples = samples(right)
        val leftStats = compareStats(leftSamples.take(requested))
        val rightStats = compareStats(rightSamples.take(requested))
        if (leftStats == null || rightStats == null) {
            val missing = listOfNotNull(left.name.takeIf { leftStats == null }, right.name.takeIf { rightStats == null })
                .joinToString(" or ") { it.take(40).asDiscordTitleText() }.replace("@", "＠")
            response.respond { content = "I couldn't find completed ${mode.title} matches for $missing. New signups need time to collect history, and a private match history can't be read." }
            return
        }
        val record = headToHead(leftSamples.map { it.summary }, rightSamples.map { it.summary })
        val embed = withContext(Dispatchers.IO) { compareEmbed(left.name, right.name, leftStats, rightStats, record, mode, requested) }
        response.respond { embeds = mutableListOf(embed) }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        println("Compare command failed: ${e.message}")
        response.respond { content = "The stat comparison is temporarily unavailable. Please try again shortly." }
    }
}
