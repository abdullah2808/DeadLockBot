package bot

import api.DeadlockClient
import api.AccountResolution
import api.SteamAccountResolver
import api.gc.MatchHistoryProvider
import api.gc.GcHistoryPermissionException
import api.gc.SteamFriendship
import api.MatchMetadataService
import api.MetadataLookup
import api.MetadataUnavailableReason
import api.PlayerNameService
import api.LiveMatchService
import api.gc.GcRateLimitedException
import models.RankRepository
import util.LiveMessageGenerator
import api.asDiscordTitleText
import kotlinx.coroutines.CancellationException
import dev.kord.rest.builder.component.actionRow
import dev.kord.core.Kord
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.core.entity.interaction.ActionInteraction
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.event.interaction.ComponentInteractionCreateEvent
import dev.kord.core.on
import data.UserRepository
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.rest.builder.interaction.string
import util.MatchMessageGenerator
import util.MatchScoreboard
import util.ScoreboardAssetLoader
import util.InteractiveMatchReport
import util.matchReportId
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.toList

suspend fun registerCommands(kord: Kord) {
    // Replace the complete global list in one request, removing obsolete commands.
    kord.createGlobalApplicationCommands {
        input("weekly", "Opt into weekly recaps, turn them off, or preview yours") {
            string("action", "enable, disable, or preview") { required = true }
        }
        input("signup", "Track your Deadlock matches") {
            string("account_id", "Your account ID, SteamID64, or Steam profile URL") { required = true }
        }
        input("unsubscribe", "Stop tracking your matches")
        input("steambot", "Add the Steam bot and check your friend status")
        input("recentmatch", "Get Your Most Recent Match") {
            string("account_id", "Your account ID, SteamID64, or Steam profile URL") { required = true }
        }
        input("trends", "Compare your latest 20 tracked matches with the previous 20")
        input("lastfive", "Get Your Last Five Matches")
        input("match", "Show a full Deadlock match scoreboard") {
            string("match_id", "Match ID (leave blank for your latest tracked match)")
        }
        input("party", "See results alongside other tracked players in this channel")
        input("profile", "Show a Deadlock profile card: rank, featured heroes and stats") {
            string("account_id", "Account ID, SteamID64, or Steam profile URL (leave blank for yours)")
        }
        input("status", "Show your tracking sync, pending reports and current cooldowns")
        input("live", "Check whether a player is in a live Deadlock match") {
            string("account_id", "Account ID, SteamID64, or Steam profile URL (leave blank for yours)")
        }
    }

    // Legacy commands in this server shadow the application's global commands.
    // Check first so subsequent starts do not repeatedly overwrite an empty list.
    val legacyGuild = Snowflake("875604309180559383")
    try {
        val legacyCommands = kord.getGuildApplicationCommands(legacyGuild).toList()
        if (legacyCommands.isNotEmpty()) {
            kord.createGuildApplicationCommands(legacyGuild) {}
            println("Removed ${legacyCommands.size} legacy server commands from guild $legacyGuild.")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A missing guild or access failure should not stop the bot's global commands.
        println("Legacy command cleanup for guild $legacyGuild failed; will retry on next startup: ${e.message}")
    }
}

suspend fun handleCommands(kord: Kord, gcProvider: MatchHistoryProvider? = null, metadata: MatchMetadataService? = null,
                           playerNames: PlayerNameService = PlayerNameService(),
                           live: LiveMatchService = LiveMatchService(gcProvider, null)) {
    registerMatchExplorer(kord)
    kord.on<ComponentInteractionCreateEvent> {
        if (!interaction.componentId.startsWith("match-report:")) return@on
        val matchId = matchReportId(interaction.componentId)
        if (matchId == null) {
            interaction.deferEphemeralResponse().respond { content = "This match button is invalid. Use `/match` to open a report." }
        } else {
            handleMatch(interaction, gcProvider, metadata, matchId.toString(), privateResponse = true)
        }
    }
    kord.on<ChatInputCommandInteractionCreateEvent> {
        val interaction = interaction
        when (interaction.command.rootName) {
            "weekly" -> handleWeekly(interaction)
            "signup" -> handleSignup(interaction, gcProvider)
            "steambot" -> {
                val account = UserRepository.selectUserByDiscordID(interaction.user.id.toString())?.accountId
                gcProvider?.updateTrackedAccounts(UserRepository.getAllUsers().map { it.accountId }.toSet())
                interaction.deferEphemeralResponse().respond {
                    content = steamOnboarding(gcProvider, account)
                    steamBotButton(gcProvider)
                }
            }
            "unsubscribe" -> handleUnsubscribe(interaction)
            "recentmatch" -> handleRecentMatch(interaction, gcProvider, metadata, playerNames)
            "trends" -> handleTrends(interaction)
            "lastfive" -> handleLastFive(interaction, kord, gcProvider, metadata)
            "match" -> handleMatch(interaction, gcProvider, metadata, interaction.command.strings["match_id"])
            "party" -> handleParty(interaction)
            "profile" -> handleProfile(interaction, gcProvider, live, playerNames)
            "status" -> handleTrackingStatus(interaction, gcProvider, metadata)
            "live" -> handleLive(interaction, gcProvider, live, playerNames)
        }
    }
}

private suspend fun handleSignup(interaction: ChatInputCommandInteraction, gc: MatchHistoryProvider?) {
    val response = interaction.deferPublicResponse()

    val rawAccountId = interaction.command.strings["account_id"]!!
    val discordId = interaction.user.id.toString()
    val channelId = interaction.channelId.toString()
    val discordUser = interaction.user.globalName ?: interaction.user.username

    // 1. Normalize the input (account id, SteamID64, or profile URL) and validate.
    val accountId = resolveAccountInput(rawAccountId) { message ->
        response.respond { content = message }
    } ?: return

    // 2. GC verification is freshest; retain the API as a signup fallback.
    val client = DeadlockClient()
    val matches = try {
        verifySignupAccount(accountId, gc, client::verifyAccount)
    } finally {
        client.close()
    }

    if (matches == null) {
        response.respond {
            content = "❌ I couldn't verify Deadlock account `$accountId` right now. " +
                "Double-check the ID and try again in a moment." +
                (gc?.let { "\n\n${steamOnboarding(it, accountId)}" } ?: "")
            steamBotButton(gc)
        }
        return
    }

    // 3. Refuse a channel that could never receive the notifications being signed up for.
    if (UserRepository.selectUserByDiscordID(discordId) == null) {
        val problem = signupChannelProblem(interaction.kord, interaction.channelId,
            interaction.data.guildId.value?.let { interaction.data.appPermissions.value }, interaction.data.channel.value?.type)
        if (problem != null) {
            response.respond {
                content = "⚠️ ${channelProblemMessage(channelId, problem)}\nYou weren't signed up. Fix that, or run `/signup` in a channel I can post in."
            }
            return
        }
    }

    // 4. Register the user and respond based on the outcome.
    when (UserRepository.addUser(discordId, accountId, channelId, discordUser)) {
        UserRepository.SignupOutcome.REGISTERED -> {
            gc?.updateTrackedAccounts(UserRepository.getAllUsers().map { it.accountId }.toSet())
            val recentMatch = matches.firstOrNull()
            response.respond {
                embeds = mutableListOf(
                    MatchMessageGenerator.buildWelcomeEmbed(discordUser, accountId, recentMatch,
                        if (gc != null && gc.friendship(accountId) != SteamFriendship.FRIEND)
                            "Your account is linked. Add the Steam bot to enable GC match access if your Deadlock privacy requires it." else null)
                )
                if (gc != null) content = steamOnboarding(gc, accountId)
                steamBotButton(gc)
            }
        }
        UserRepository.SignupOutcome.ALREADY_REGISTERED -> response.respond {
            content = "⚠️ You're already registered. Use `/unsubscribe` first if you want to change your account ID." +
                (gc?.let { "\n\n${steamOnboarding(it, UserRepository.selectUserByDiscordID(discordId)?.accountId)}" } ?: "")
            steamBotButton(gc)
        }
        UserRepository.SignupOutcome.ACCOUNT_ID_IN_USE -> response.respond {
            content = "⚠️ Deadlock account `$accountId` is already being tracked by someone else."
        }
    }
}

private suspend fun handleRecentMatch(interaction: ChatInputCommandInteraction, gc: MatchHistoryProvider?, metadata: MatchMetadataService?, playerNames: PlayerNameService) {
    val response = interaction.deferEphemeralResponse()
    val accountId = resolveAccountInput(interaction.command.strings["account_id"]!!) { message ->
        response.respond { content = message }
    } ?: return
    val client = DeadlockClient()
    try {
        val fetchMetadata: suspend (Long) -> models.MatchDTO? = { id ->
            if (metadata != null) metadata.metadataFor(accountId, id, interactive = true) else client.getMatchByMatchID(id)
        }
        val result = RecentMatchLookup(gc, client::getRecentMatch, fetchMetadata).lookup(accountId, 1)
        val match = result.matches.firstOrNull()
        if (match == null) {
            response.respond {
                content = result.latestId?.let {
                    "Your latest match is `$it`, but its details aren't available yet. Please try again later."
                } ?: "No recent matches were found for this account."
            }
            return
        }
        val info = result.metadata[match.matchId] ?: fetchMetadata(match.matchId)
        val playerName = playerNames.nameFor(accountId).asDiscordTitleText()
        MatchMessageGenerator.generateRecentMatch(match, playerName, interaction.channel, info)
        response.respond { content = "✅ Fetched match `${match.matchId}` for `$accountId`." }
    } catch (e: GcHistoryPermissionException) {
        response.respond {
            content = "Deadlock GC denied match access for `$accountId`.\n\n${steamOnboarding(gc, accountId)}"
            steamBotButton(gc)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("Recent match command failed: ${e.message}")
        val problem = channelProblem(e)
        response.respond {
            content = if (problem != null) "${channelProblemMessage(interaction.channelId.toString(), problem)} Try `/recentmatch` in a channel I can post in."
            else "I couldn't retrieve your latest match right now. Please try again later."
        }
    } finally {
        client.close()
    }
}

/** The account named in the optional `account_id`, else the caller's linked account. */
private suspend fun requestedOrLinkedAccount(interaction: ChatInputCommandInteraction, onError: suspend (String) -> Unit): String? {
    interaction.command.strings["account_id"]?.let { return resolveAccountInput(it, onError) }
    return UserRepository.selectUserByDiscordID(interaction.user.id.toString())?.accountId
        ?: run { onError("Provide an `account_id`, or use `/signup` to link your account."); null }
}

private suspend fun handleProfile(interaction: ChatInputCommandInteraction, gc: MatchHistoryProvider?,
                                  live: LiveMatchService, playerNames: PlayerNameService) {
    val response = interaction.deferPublicResponse()
    val accountId = requestedOrLinkedAccount(interaction) { message -> response.respond { content = message } } ?: return
    if (gc == null) {
        response.respond { content = "Profile cards come from Valve's Game Coordinator, which needs the Steam bot; it isn't configured on this deployment." }
        return
    }
    try {
        val card = live.profileCard(accountId)
        if (card == null) {
            response.respond { content = "I couldn't load the profile card for `$accountId` right now. Please try again shortly." }
            return
        }
        val rank = card.rankedBadgeLevel?.let { badge -> withContext(Dispatchers.IO) { RankRepository.describeBadge(badge) } }
        val name = playerNames.nameFor(accountId).asDiscordTitleText()
        response.respond { embeds = mutableListOf(LiveMessageGenerator.profileEmbed(name, card, rank)) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: GcRateLimitedException) {
        response.respond { content = "Valve is rate limiting the bot. ${e.message}." }
    } catch (e: Exception) {
        println("Profile command failed for $accountId: ${e.message}")
        response.respond { content = "I couldn't load the profile card for `$accountId` right now. Please try again shortly." }
    }
}

private suspend fun handleLive(interaction: ChatInputCommandInteraction, gc: MatchHistoryProvider?,
                               live: LiveMatchService, playerNames: PlayerNameService) {
    val response = interaction.deferPublicResponse()
    val accountId = requestedOrLinkedAccount(interaction) { message -> response.respond { content = message } } ?: return
    val name = playerNames.nameFor(accountId).asDiscordTitleText()
    val inGame = gc?.playingDeadlock(accountId)
    val match = try {
        live.findLive(accountId.toLong())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("Live command could not load active matches: ${e.message}")
        response.respond { content = liveUnavailableMessage(name, inGame) }
        return
    }
    if (match == null) {
        response.respond { content = notLiveMessage(name, inGame) }
        return
    }
    val trackedNames = UserRepository.getAllUsers()
        .mapNotNull { user -> user.accountId.toLongOrNull()?.let { it to user.discordUser.asDiscordTitleText() } }
        .filter { (id, _) -> match.players.any { it.accountId == id } }
        .toMap()
    response.respond { embeds = mutableListOf(LiveMessageGenerator.liveEmbed(name, accountId.toLong(), match, trackedNames)) }
}

internal fun liveUnavailableMessage(name: String, inGame: Boolean?) =
    if (inGame == true) "🟢 $name is in Deadlock right now, but Valve's live match list is unavailable. Try again shortly."
    else "I couldn't reach the live match list right now. Please try again shortly."

/** Valve's Watch list only features a subset of games, so absence from it is not proof of being offline. */
internal fun notLiveMessage(name: String, inGame: Boolean?) = when (inGame) {
    true -> "🟢 $name is in Deadlock, but their match isn't in Valve's Watch list, which only features a subset of live games."
    false -> "⚫ $name isn't playing Deadlock right now."
    null -> "$name isn't in any match on Valve's Watch list, which only features a subset of live games. " +
        "Players who are Steam friends with the bot (`/steambot`) also show whether they're in game."
}

private suspend fun resolveAccountInput(raw: String, onError: suspend (String) -> Unit): String? {
    return when (val result = SteamAccountResolver().use { it.resolve(raw) }) {
        is AccountResolution.Resolved -> result.accountId
        AccountResolution.Invalid -> {
            onError("Please enter a numeric account ID, a 17-digit SteamID64, or a Steam profile URL " +
                "(`steamcommunity.com/profiles/...` or `steamcommunity.com/id/...`).")
            null
        }
        AccountResolution.Unavailable -> {
            onError("I couldn't resolve that Steam profile right now. Try again in a moment, " +
                "or enter its numeric SteamID64/account ID.")
            null
        }
    }
}

private suspend fun handleUnsubscribe(interaction: ChatInputCommandInteraction) {
    val discordId = interaction.user.id.toString()
    data.WeeklyDigestRepository.disable(discordId)
    UserRepository.removeUser(discordId)
    interaction.deferPublicResponse().respond {
        content = "❌ You’ve been unsubscribed from match tracking."
    }
}

private suspend fun handleLastFive(interaction: ChatInputCommandInteraction, kord: Kord, gc: MatchHistoryProvider?, metadata: MatchMetadataService?) {
    val response = interaction.deferEphemeralResponse()
    val discordId = interaction.user.id.toString()
    val user = UserRepository.selectUserByDiscordID(discordId)
    if (user == null) {
        response.respond {
            content = "User not registered, please register"
        }
        return
    }

    val client = DeadlockClient()
    try {
        val result = RecentMatchLookup(gc, client::getRecentMatch, { id ->
            if (metadata != null) metadata.metadataFor(user.accountId, id, interactive = true) else client.getMatchByMatchID(id)
        }).lookup(user.accountId, 5)
        val waiting = result.unavailableIds.takeIf { it.isNotEmpty() }?.joinToString { "`$it`" }
        val notice = waiting?.let { "Waiting for match details: $it. Some recent matches are not shown yet." }
        if (result.matches.isEmpty()) {
            response.respond { content = notice ?: "No recent matches were found for this account." }
            return
        }
        trackingDelivery(listOf(user.accountId)) {
            MatchMessageGenerator.generateLastFive(result.matches, user.discordUser, deliveryChannel(kord, user.channelId), notice)
        }
        response.respond { content = lastFiveConfirmation(user.channelId, interaction.channelId.toString(), notice) }
    } catch (e: GcHistoryPermissionException) {
        response.respond {
            content = "Deadlock GC denied match access for `${user.accountId}`.\n\n${steamOnboarding(gc, user.accountId)}"
            steamBotButton(gc)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("Last five command failed: ${e.message}")
        val problem = channelProblem(e)
        response.respond {
            content = if (problem != null) "`/lastfive` posts in your tracking channel, but: ${channelProblemMessage(user.channelId, problem)}\n$MOVE_TRACKING_HINT"
            else "I couldn't retrieve your recent matches right now. Please try again later."
        }
    } finally {
        client.close()
    }
}

/** `/lastfive` always posts in the registered channel, so a run from anywhere else has to say where it went. */
internal fun lastFiveConfirmation(registeredChannelId: String?, commandChannelId: String, notice: String?): String =
    if (registeredChannelId == commandChannelId) notice ?: "✅ Fetched your recent matches."
    else listOfNotNull(notice, "✅ Posted your recent matches in <#$registeredChannelId>, your tracking channel.").joinToString("\n")

internal fun parseMatchId(value: String): Long? = value.trim().removePrefix("match_id:").trim().toLongOrNull()?.takeIf { it > 0 }

/** Say why a report can't be built yet, rather than always blaming processing. */
internal fun metadataUnavailableMessage(matchId: Long, outcome: MetadataLookup.Unavailable, canSignUp: Boolean = false): String {
    val retry = outcome.retryInMillis?.takeIf { it > 0 }?.let {
        val minutes = ((it + 59_999) / 60_000).coerceAtLeast(1)
        " Try again in about $minutes minute${if (minutes == 1L) "" else "s"}."
    } ?: " Try again in a few minutes."
    val background = if (outcome.retrying) " The bot keeps retrying in the background." else ""
    return when (outcome.reason) {
        MetadataUnavailableReason.INVALID_MATCH ->
            "Valve reports no metadata for match `$matchId`. The match may have been cancelled, or the ID may be wrong."
        MetadataUnavailableReason.ACCESS_DENIED ->
            "Valve denied the bot access to match `$matchId`. A registered player from this match can add the Steam bot " +
                "(see `/steambot`); Valve's privacy rules decide whether that grants access."
        MetadataUnavailableReason.PROCESSING ->
            "Valve is still publishing the data for match `$matchId`; this usually takes a few minutes after a match ends.$retry$background"
        MetadataUnavailableReason.WAITING_FOR_LOOKUP ->
            "Match `$matchId` isn't in the public Deadlock API archive yet, and the bot's Steam lookups are paced " +
                "to avoid Valve rate limits.$retry$background"
        MetadataUnavailableReason.NOT_STORED ->
            "Match `$matchId` isn't in the public Deadlock API archive yet.$retry$background" +
                if (canSignUp) " Matches with a registered player can be fetched from Valve directly; use `/signup` to link your account." else ""
        MetadataUnavailableReason.ERROR ->
            "Full metadata for match `$matchId` is temporarily unavailable from both the public API and Valve.$retry$background"
    }
}

private suspend fun handleMatch(interaction: ActionInteraction, gc: MatchHistoryProvider?, metadata: MatchMetadataService?,
                                rawMatchId: String?, privateResponse: Boolean = false) {
    // Acknowledge before API calls and image rendering to keep the interaction alive.
    val response = if (privateResponse) interaction.deferEphemeralResponse() else interaction.deferPublicResponse()
    val requestedId = rawMatchId?.let(::parseMatchId)
    if (rawMatchId != null && requestedId == null) {
        response.respond { content = "Choose `/match`, select the `match_id` option (Tab on desktop), then paste a positive match ID such as `12345678`." }
        return
    }
    val user = UserRepository.selectUserByDiscordID(interaction.user.id.toString())
    if (requestedId == null && user == null) {
        response.respond { content = "Use `/signup` to link your account, or provide a `match_id` to view any match." }
        return
    }
    val client = DeadlockClient()
    try {
        // Unregistered viewers (e.g. clicking another player's notification) still reach tracked participants' GC access.
        val fetchMetadata: suspend (Long) -> MetadataLookup = { id ->
            metadata?.lookup(user?.accountId, id, interactive = true, viewer = requestedId != null)
                ?: client.getMatchByMatchID(id)?.let { MetadataLookup.Found(it) }
                ?: MetadataLookup.Unavailable(MetadataUnavailableReason.NOT_STORED)
        }
        val recent = if (requestedId == null) RecentMatchLookup(gc, client::getRecentMatch) { id ->
            (fetchMetadata(id) as? MetadataLookup.Found)?.match
        }.lookup(user!!.accountId, 1) else null
        val matchId = requestedId ?: recent?.latestId ?: recent?.matches?.firstOrNull()?.matchId
        if (matchId == null) {
            response.respond { content = "No recent matches found for your linked account." }
            return
        }
        val lookup = recent?.metadata?.get(matchId)?.let { MetadataLookup.Found(it) } ?: fetchMetadata(matchId)
        val match = (lookup as? MetadataLookup.Found)?.match?.takeIf { it.matchInfo.players.isNotEmpty() }
        if (match == null) {
            val outcome = lookup as? MetadataLookup.Unavailable ?: MetadataLookup.Unavailable(MetadataUnavailableReason.ERROR)
            println("Full match report for $matchId unavailable: ${outcome.reason}.")
            response.respond {
                content = metadataUnavailableMessage(matchId, outcome, canSignUp = user == null && gc != null)
                if (outcome.reason == MetadataUnavailableReason.ACCESS_DENIED) steamBotButton(gc)
            }
            return
        }
        val assets = ScoreboardAssetLoader.load(match)
        val highlighted = user?.accountId?.toLongOrNull()?.toInt()
        val png = withContext(Dispatchers.Default) { MatchScoreboard.render(match, matchId, assets, highlighted) }
        val html = withContext(Dispatchers.Default) { InteractiveMatchReport.render(match, matchId, assets, highlighted) }
        val reportKey = MatchExplorer.store(match, matchId, assets)
        response.respond {
            actionRow { interactionButton(dev.kord.common.entity.ButtonStyle.Primary, "mx:$reportKey:open") { label = "Explore match" } }
            content = "**Deadlock • Match $matchId**\nFull team scoreboard · K/D/A · souls · damage · healing · final items\n" +
                "Click **Explore match** for private stats, builds, abilities and event timelines. " +
                "Download the `.html` report for the full browser view."
            addFile("deadlock-match-$matchId.png", ChannelProvider(png.size.toLong()) { ByteReadChannel(png) })
            addFile("deadlock-match-$matchId.html", ChannelProvider(html.size.toLong()) { ByteReadChannel(html) })
        }
    } catch (e: GcHistoryPermissionException) {
        response.respond {
            content = "Deadlock GC denied match access for your linked account.\n\n${steamOnboarding(gc, user?.accountId)}"
            steamBotButton(gc)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("Full match command failed: ${e.message}")
        response.respond { content = "I couldn't generate this scoreboard right now. Please try again shortly." }
    } finally {
        client.close()
    }
}
