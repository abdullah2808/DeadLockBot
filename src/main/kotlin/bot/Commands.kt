package bot

import api.DeadlockClient
import api.AccountResolution
import api.SteamAccountResolver
import api.gc.MatchHistoryProvider
import api.gc.GcHistoryPermissionException
import api.gc.SteamFriendship
import api.MatchMetadataService
import api.PlayerNameService
import api.asDiscordTitleText
import kotlinx.coroutines.CancellationException
import dev.kord.rest.builder.component.actionRow
import dev.kord.core.Kord
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.on
import data.UserRepository
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.rest.builder.interaction.string
import util.MatchMessageGenerator
import util.MatchScoreboard
import util.ScoreboardAssetLoader
import util.InteractiveMatchReport
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.toList

suspend fun registerCommands(kord: Kord) {
    // Replace the complete global list in one request, removing obsolete commands.
    kord.createGlobalApplicationCommands {
        input("signup", "Track your Deadlock matches") {
            string("account_id", "Your account ID, SteamID64, or Steam profile URL") { required = true }
        }
        input("unsubscribe", "Stop tracking your matches")
        input("steambot", "Add the Steam bot and check your friend status")
        input("recentmatch", "Get Your Most Recent Match") {
            string("account_id", "Your account ID, SteamID64, or Steam profile URL") { required = true }
        }
        input("lastfive", "Get Your Last Five Matches")
        input("match", "Show a full Deadlock match scoreboard") {
            string("match_id", "Match ID (leave blank for your latest tracked match)")
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
                           playerNames: PlayerNameService = PlayerNameService()) {
    registerMatchExplorer(kord)
    kord.on<ChatInputCommandInteractionCreateEvent> {
        val interaction = interaction
        when (interaction.command.rootName) {
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
            "recentmatch" -> handleRecentMatch(interaction, kord, gcProvider, metadata, playerNames)
            "lastfive" -> handleLastFive(interaction, kord, gcProvider, metadata)
            "match" -> handleMatch(interaction, gcProvider, metadata)
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

    // 3. Register the user and respond based on the outcome.
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

private suspend fun handleRecentMatch(interaction: ChatInputCommandInteraction, kord: Kord, gc: MatchHistoryProvider?, metadata: MatchMetadataService?, playerNames: PlayerNameService) {
    val response = interaction.deferEphemeralResponse()
    val accountId = resolveAccountInput(interaction.command.strings["account_id"]!!) { message ->
        response.respond { content = message }
    } ?: return
    val client = DeadlockClient()
    try {
        val fetchMetadata: suspend (Long) -> models.MatchDTO? = { id ->
            if (metadata != null) metadata.metadataFor(accountId, id) else client.getMatchByMatchID(id)
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
        val channel = requireNotNull(kord.getChannelOf<dev.kord.core.entity.channel.TextChannel>(interaction.channelId))
        val playerName = playerNames.nameFor(accountId).asDiscordTitleText()
        MatchMessageGenerator.generateRecentMatch(match, playerName, channel, info)
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
        response.respond { content = "I couldn't retrieve your latest match right now. Please try again later." }
    } finally {
        client.close()
    }
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
            if (metadata != null) metadata.metadataFor(user.accountId, id) else client.getMatchByMatchID(id)
        }).lookup(user.accountId, 5)
        val waiting = result.unavailableIds.takeIf { it.isNotEmpty() }?.joinToString { "`$it`" }
        val notice = waiting?.let { "Waiting for match details: $it. Some recent matches are not shown yet." }
        if (result.matches.isEmpty()) {
            response.respond { content = notice ?: "No recent matches were found for this account." }
            return
        }
        val channel = requireNotNull(kord.getChannelOf<dev.kord.core.entity.channel.TextChannel>(Snowflake(user.channelId.toString())))
        MatchMessageGenerator.generateLastFive(result.matches, user.discordUser, channel, notice)
        response.respond { content = notice ?: "✅ Fetched your recent matches." }
    } catch (e: GcHistoryPermissionException) {
        response.respond {
            content = "Deadlock GC denied match access for `${user.accountId}`.\n\n${steamOnboarding(gc, user.accountId)}"
            steamBotButton(gc)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("Last five command failed: ${e.message}")
        response.respond { content = "I couldn't retrieve your recent matches right now. Please try again later." }
    } finally {
        client.close()
    }
}

internal fun parseMatchId(value: String): Long? = value.trim().removePrefix("match_id:").trim().toLongOrNull()?.takeIf { it > 0 }

private suspend fun handleMatch(interaction: ChatInputCommandInteraction, gc: MatchHistoryProvider?, metadata: MatchMetadataService?) {
    // Acknowledge before API calls and image rendering to keep the interaction alive.
    val response = interaction.deferPublicResponse()
    val rawMatchId = interaction.command.strings["match_id"]
    val requestedId = rawMatchId?.let(::parseMatchId)
    if (rawMatchId != null && requestedId == null) {
        response.respond { content = "Please enter a positive numeric match ID, e.g. `/match 12345678`." }
        return
    }
    val user = UserRepository.selectUserByDiscordID(interaction.user.id.toString())
    if (requestedId == null && user == null) {
        response.respond { content = "Use `/signup` to link your account, or provide a `match_id` to view any match." }
        return
    }
    val client = DeadlockClient()
    try {
        val fetchMetadata: suspend (Long) -> models.MatchDTO? = { id ->
            if (metadata != null && user != null) metadata.metadataFor(user.accountId, id) else client.getMatchByMatchID(id)
        }
        val recent = if (requestedId == null) RecentMatchLookup(gc, client::getRecentMatch, fetchMetadata).lookup(user!!.accountId, 1) else null
        val matchId = requestedId ?: recent?.latestId ?: recent?.matches?.firstOrNull()?.matchId
        if (matchId == null) {
            response.respond { content = "No recent matches found for your linked account." }
            return
        }
        val match = recent?.metadata?.get(matchId) ?: fetchMetadata(matchId)
        if (match == null || match.matchInfo.players.isEmpty()) {
            response.respond { content = "Full metadata for match `$matchId` is unavailable. It may still be processing; try again later." }
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
