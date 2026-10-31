package bot

import data.CachedPlayerMatchTable
import data.MatchCacheRepository
import data.UserRepository
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.core.behavior.interaction.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import models.MatchHistoryDTO
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.Locale

internal data class PartyMember(val account: String, val name: String, val matches: List<MatchHistoryDTO>)
internal data class PartyResult(val member: PartyMember, val games: Int, val wins: Int, val kills: Double,
    val deaths: Double, val assists: Double, val combo: Pair<Int, Int>)

/** Matching team and match id establishes teammates, never a premade party or friendship. */
internal fun partyResults(own: List<MatchHistoryDTO>, members: List<PartyMember>): List<PartyResult> {
    val valid = own.filter { it.playerTeam in 0..1 && it.matchResult in 0..1 && it.matchDurationSeconds > 0 }
        .distinctBy { it.matchId }.associateBy { it.matchId }
    return members.mapNotNull { member ->
        val shared = member.matches.distinctBy { it.matchId }.mapNotNull { other ->
            valid[other.matchId]?.takeIf { it.playerTeam == other.playerTeam && it.matchResult == other.matchResult }
                ?.let { it to other }
        }
        if (shared.isEmpty()) return@mapNotNull null
        val combo = shared.groupingBy { it.first.heroId to it.second.heroId }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<Pair<Int, Int>, Int>> { it.value }.thenBy { it.key.first }.thenBy { it.key.second }).first().key
        PartyResult(member, shared.size, shared.count { it.first.matchResult == it.first.playerTeam },
            shared.sumOf { it.first.kills.toLong() }.toDouble() / shared.size,
            shared.sumOf { it.first.deaths.toLong() }.toDouble() / shared.size,
            shared.sumOf { it.first.assists.toLong() }.toDouble() / shared.size, combo)
    }.sortedWith(compareByDescending<PartyResult> { it.games }.thenBy { it.member.account }).take(6)
}

internal fun partyReport(results: List<PartyResult>, heroName: (Int) -> String): String = buildString {
    append("**Your tracked teammates**\nSame-team results with other players tracking into this channel.\n")
    if (results.isEmpty()) append("\nNo shared completed matches found yet.")
    results.forEach { r ->
        fun number(n: Double) = String.format(Locale.US, "%.1f", n)
        val name = r.member.name.replace("@", "＠").replace(Regex("[\\`*_~|<>\n\r]"), "").take(40)
        append("\n**$name** · ${r.games} games · ${r.wins}W/${r.games - r.wins}L · ${number(100.0 * r.wins / r.games)}%")
        append("\nYour average K/D/A: ${number(r.kills)}/${number(r.deaths)}/${number(r.assists)}")
        append("\nMost played pairing: ${heroName(r.combo.first).take(30)} + ${heroName(r.combo.second).take(30)}\n")
    }
    append("\nRetained tracker history only; older matches may be missing. Small samples are descriptive. Sharing a team does not prove you queued together.")
}.take(1950)

internal suspend fun handleParty(interaction: ChatInputCommandInteraction) {
    val response = interaction.deferWithVisibility()
    try {
        val text = withContext(Dispatchers.IO) {
            val user = UserRepository.selectUserByDiscordID(interaction.user.id.toString())
                ?: return@withContext "Use `/signup` to link your account first."
            val channel = interaction.channelId.toString()
            if (user.channelId != channel) return@withContext "Run `/party` in your registered tracking channel."
            val peers = UserRepository.getAllUsers().filter { it.channelId == channel && it.accountId != user.accountId }
            fun history(account: String): List<MatchHistoryDTO> = transaction {
                CachedPlayerMatchTable.selectAll().where { CachedPlayerMatchTable.accountId eq account }
                    .orderBy(CachedPlayerMatchTable.matchId to SortOrder.DESC).limit(40)
                    .mapNotNull { MatchCacheRepository.find(account, it[CachedPlayerMatchTable.matchId]) }
            }
            val results = partyResults(history(user.accountId), peers.map { PartyMember(it.accountId, it.discordUser, history(it.accountId)) })
            partyReport(results, models.HeroRepository::getHeroName)
        }
        response.respond { content = text }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) { response.respond { content = "Tracked teammate stats are temporarily unavailable. Please try again shortly." } }
}
