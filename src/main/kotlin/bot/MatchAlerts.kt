package bot

import api.LiveMatchService
import data.LiveAlertRepository
import data.UserRepository
import dev.kord.common.entity.Permission
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.core.entity.interaction.GuildInteraction
import dev.kord.rest.request.RestRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import models.ActiveMatchDTO
import util.LiveMessageGenerator

private const val ANNOUNCED_RETENTION_MILLIS = 24 * 60 * 60_000L

internal data class MatchAlert(val channelId: String, val listed: ListedMatch)

/** One alert per match per opted-in channel, for matches a tracked player of that channel is still in. */
internal fun pendingMatchAlerts(
    users: List<UserRepository.UserRecord>,
    enabledChannels: Set<String>,
    matches: List<ActiveMatchDTO>,
    announced: Set<Pair<String, Long>>,
): List<MatchAlert> = users.filter { it.channelId in enabledChannels }.groupBy { it.channelId!! }.flatMap { (channel, members) ->
    val players = members.mapNotNull { user -> user.accountId.toLongOrNull()?.let { ChannelPlayer(it, user.discordUser) } }
    activeBoard(players, matches).listed.mapNotNull { listed ->
        val present = listed.players.filter { it.seat.abandoned != true }
        if (present.isEmpty() || (channel to listed.match.matchId) in announced) null
        else MatchAlert(channel, listed.copy(players = present))
    }
}

/**
 * Claim before sending, because repeating an unprompted post is worse than missing one: a crash in between loses
 * that alert instead of reposting it. A failed send gives the claim back for the next poll unless it can't recover.
 */
internal suspend fun deliverMatchAlerts(
    alerts: List<MatchAlert>,
    claim: (MatchAlert) -> Unit,
    send: suspend (MatchAlert) -> Unit,
    release: (MatchAlert) -> Unit,
    permanent: (Exception) -> Boolean = { false },
) {
    for (alert in alerts) {
        try {
            claim(alert)
        } catch (e: Exception) {
            println("Could not record the alert for match ${alert.listed.match.matchId}; not posting it: ${e.message}")
            continue
        }
        try {
            send(alert)
        } catch (e: CancellationException) {
            throw e // Shutting down: Discord may already have the post, so the claim stays.
        } catch (e: Exception) {
            println("Could not post the alert for match ${alert.listed.match.matchId} to ${alert.channelId}: ${e.message}")
            if (!permanent(e)) try { release(alert) } catch (failure: Exception) {
                println("Could not release the alert claim: ${failure.message}")
            }
        }
    }
}

/** The live list is only fetched when an opted-in channel has someone to alert about, so alerts cost nothing until used. */
internal suspend fun checkMatchAlerts(
    users: List<UserRepository.UserRecord>,
    enabledChannels: () -> Set<String>,
    fetchMatches: suspend () -> List<ActiveMatchDTO>,
    announced: (Set<String>) -> Set<Pair<String, Long>>,
    deliver: suspend (List<MatchAlert>) -> Unit,
) {
    val channels = enabledChannels()
    if (users.none { it.channelId in channels }) return
    val matches = fetchMatches()
    deliver(pendingMatchAlerts(users, channels, matches, announced(channels)))
}

/** Best effort, and called once per source poll so alerts add no polling cadence of their own. */
internal suspend fun announceLiveMatches(kord: Kord, users: List<UserRepository.UserRecord>, live: LiveMatchService) {
    try {
        checkMatchAlerts(
            users,
            enabledChannels = { LiveAlertRepository.enabledChannels() },
            fetchMatches = { live.activeMatches(background = true).matches },
            announced = { channels ->
                LiveAlertRepository.prune(System.currentTimeMillis() - ANNOUNCED_RETENTION_MILLIS)
                LiveAlertRepository.announced(channels)
            },
            deliver = { alerts ->
                deliverMatchAlerts(
                    alerts,
                    claim = { LiveAlertRepository.claim(it.channelId, it.listed.match.matchId) },
                    send = { alert ->
                        val embed = withContext(Dispatchers.IO) { LiveMessageGenerator.matchAlertEmbed(alert.listed) }
                        MessageChannelBehavior(Snowflake(alert.channelId), kord).createMessage { embeds = mutableListOf(embed) }
                        println("Posted live alert for match ${alert.listed.match.matchId} to ${alert.channelId}.")
                    },
                    release = { LiveAlertRepository.release(it.channelId, it.listed.match.matchId) },
                    // A deleted channel or lost access won't recover before the match ends, so don't retry every poll.
                    permanent = { it is RestRequestException && it.status.code in setOf(403, 404) },
                )
            },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("Live match alert check failed: ${e.message}")
    }
}

internal const val MATCH_ALERT_COVERAGE = "Only matches on Valve's Watch list are detected. It features only some live games, " +
    "so matches outside it never alert, and an alert can arrive minutes after a match starts."

/** Alerts post for everyone tracked here, so changing them takes a tracked player of this channel or a channel manager. */
internal fun canChangeMatchAlerts(registeredHere: Boolean, managesChannels: Boolean) = registeredHere || managesChannels

internal fun matchAlertsReply(action: String?, enabled: Boolean, allowed: Boolean, trackedHere: Int): String {
    fun tracked() = if (trackedHere == 0) "\nNobody is tracked in this channel yet, so nothing will post until someone uses `/signup` here." else ""
    return when (action) {
        "enable", "disable" -> if (!allowed) {
            "Only a player tracked in this channel, or someone who can manage channels, can change match alerts here. " +
                "They are currently ${if (enabled) "on" else "off"}."
        } else if (action == "enable") {
            "Match alerts are on for this channel. I'll post once per match when a tracked player from this channel appears in one.\n" +
                MATCH_ALERT_COVERAGE + tracked()
        } else "Match alerts are off for this channel."
        "status" -> "Match alerts are ${if (enabled) "on" else "off"} for this channel. Tracked players here: $trackedHere.\n$MATCH_ALERT_COVERAGE"
        else -> "Choose `/matchalerts action:enable`, `action:disable` or `action:status`."
    }
}

internal suspend fun handleMatchAlerts(interaction: ChatInputCommandInteraction) {
    val response = interaction.deferEphemeralResponse()
    try {
        val channel = interaction.channelId.toString()
        val action = interaction.command.strings["action"]?.lowercase()
        val managesChannels = (interaction as? GuildInteraction)?.permissions?.contains(Permission.ManageChannels) == true
        val text = withContext(Dispatchers.IO) {
            val tracked = UserRepository.getAllUsers().filter { it.channelId == channel }
            val allowed = canChangeMatchAlerts(tracked.any { it.discordId == interaction.user.id.toString() }, managesChannels)
            if (allowed && action == "enable") LiveAlertRepository.enable(channel, interaction.user.id.toString())
            if (allowed && action == "disable") LiveAlertRepository.disable(channel)
            matchAlertsReply(action, LiveAlertRepository.isEnabled(channel), allowed, tracked.size)
        }
        response.respond { content = text }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("Match alert settings failed: ${e.message}")
        response.respond { content = "Match alert settings are temporarily unavailable. Please try again shortly." }
    }
}
