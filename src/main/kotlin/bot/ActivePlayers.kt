package bot

import api.LiveMatchService
import api.gc.MatchHistoryProvider
import data.UserRepository
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import models.ActiveMatchDTO
import models.ActiveMatchPlayer
import util.LiveMessageGenerator

/** [inDeadlock] is Steam presence: null when the bot can't see it, and true doesn't prove they are in a match. */
internal data class ChannelPlayer(val accountId: Long, val name: String, val inDeadlock: Boolean? = null)
internal data class SeatedPlayer(val player: ChannelPlayer, val seat: ActiveMatchPlayer)
internal data class ListedMatch(val match: ActiveMatchDTO, val players: List<SeatedPlayer>)
internal data class ActiveBoard(val listed: List<ListedMatch>, val inDeadlock: List<ChannelPlayer>, val notSeen: List<ChannelPlayer>)

/** Each player's best known status, grouping those who share a listed match. */
internal fun activeBoard(players: List<ChannelPlayer>, matches: List<ActiveMatchDTO>): ActiveBoard {
    // Someone who left one listed match and joined another belongs with the match they are still in.
    val seats = matches.flatMap { match -> match.players.map { match to it } }
        .sortedBy { (_, seat) -> seat.abandoned == true }
        .distinctBy { (_, seat) -> seat.accountId }
        .associateBy { (_, seat) -> seat.accountId }
    val (seated, unseated) = players.partition { it.accountId in seats }
    val listed = seated.groupBy { seats.getValue(it.accountId).first.matchId }.values.map { group ->
        ListedMatch(seats.getValue(group.first().accountId).first, group
            .map { SeatedPlayer(it, seats.getValue(it.accountId).second) }
            .sortedWith(compareBy({ it.seat.team }, { it.player.name })))
    }.sortedWith(compareBy({ it.match.startTime }, { it.match.matchId }))
    val (inDeadlock, notSeen) = unseated.sortedBy { it.name }.partition { it.inDeadlock == true }
    return ActiveBoard(listed, inDeadlock, notSeen)
}

internal suspend fun handleActive(interaction: ChatInputCommandInteraction, gc: MatchHistoryProvider?, live: LiveMatchService) {
    val response = interaction.deferWithVisibility()
    try {
        val channel = interaction.channelId.toString()
        val members = withContext(Dispatchers.IO) { UserRepository.getAllUsers() }.filter { it.channelId == channel }
        if (members.isEmpty()) {
            response.respond { content = "Nobody is tracked in this channel yet. Use `/signup` here to appear in `/active`." }
            return
        }
        val snapshot = try {
            live.activeMatches()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Active command could not load live matches: ${e.message}")
            null
        }
        val players = members.mapNotNull { user ->
            user.accountId.toLongOrNull()?.let { ChannelPlayer(it, user.discordUser, gc?.playingDeadlock(user.accountId)) }
        }
        val embed = withContext(Dispatchers.IO) {
            LiveMessageGenerator.activeEmbed(activeBoard(players, snapshot?.matches.orEmpty()), snapshot?.fromGc, steamBotConfigured = gc != null)
        }
        response.respond { embeds = mutableListOf(embed) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("Active command failed: ${e.message}")
        response.respond { content = "I couldn't check who's playing right now. Please try again shortly." }
    }
}
