package bot

import api.DeadlockClient
import api.AccountResolution
import api.SteamAccountResolver
import api.gc.MatchHistoryProvider
import kotlinx.coroutines.CancellationException
import dev.kord.core.Kord
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.on
import data.UserRepository
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.rest.builder.interaction.string
import util.MatchMessageGenerator

suspend fun registerCommands(kord: Kord) {
    kord.createGlobalChatInputCommand("signup", "Track your Deadlock matches") {
        string("account_id", "Your account ID, SteamID64, or Steam profile URL") { required = true }
    }

    kord.createGlobalChatInputCommand("unsubscribe", "Stop tracking your matches")

    kord.createGlobalChatInputCommand("recentmatch", "Get Your Most Recent Match") {
        string("account_id", "Your account ID, SteamID64, or Steam profile URL") { required = true }
    }

    kord.createGlobalChatInputCommand("lastfive", "Get Your Last Five Matches") {
    }

}

suspend fun handleCommands(kord: Kord, gcProvider: MatchHistoryProvider? = null) {
    kord.on<ChatInputCommandInteractionCreateEvent> {
        val interaction = interaction
        when (interaction.command.rootName) {
            "signup" -> handleSignup(interaction)
            "unsubscribe" -> handleUnsubscribe(interaction)
            "recentmatch" -> handleRecentMatch(interaction, kord, gcProvider)
            "lastfive" -> handleLastFive(interaction, kord, gcProvider)
        }
    }
}

private suspend fun handleSignup(interaction: ChatInputCommandInteraction) {
    val response = interaction.deferPublicResponse()

    val rawAccountId = interaction.command.strings["account_id"]!!
    val discordId = interaction.user.id.toString()
    val channelId = interaction.channelId.toString()
    val discordUser = interaction.user.globalName ?: interaction.user.username

    // 1. Normalize the input (account id, SteamID64, or profile URL) and validate.
    val accountId = resolveAccountInput(rawAccountId) { message ->
        response.respond { content = message }
    } ?: return

    // 2. Verify the account actually resolves against the Deadlock API before saving.
    val client = DeadlockClient()
    val matches = try {
        client.verifyAccount(accountId)
    } finally {
        client.close()
    }

    if (matches == null) {
        response.respond {
            content = "❌ I couldn't verify Deadlock account `$accountId` right now. " +
                "Double-check the ID and try again in a moment."
        }
        return
    }

    // 3. Register the user and respond based on the outcome.
    when (UserRepository.addUser(discordId, accountId, channelId, discordUser)) {
        UserRepository.SignupOutcome.REGISTERED -> {
            val recentMatch = matches.firstOrNull()
            response.respond {
                embeds = mutableListOf(
                    MatchMessageGenerator.buildWelcomeEmbed(discordUser, accountId, recentMatch)
                )
            }
        }
        UserRepository.SignupOutcome.ALREADY_REGISTERED -> response.respond {
            content = "⚠️ You're already registered. Use `/unsubscribe` first if you want to change your account ID."
        }
        UserRepository.SignupOutcome.ACCOUNT_ID_IN_USE -> response.respond {
            content = "⚠️ Deadlock account `$accountId` is already being tracked by someone else."
        }
    }
}

private suspend fun handleRecentMatch(interaction: ChatInputCommandInteraction, kord: Kord, gc: MatchHistoryProvider?) {
    val response = interaction.deferEphemeralResponse()
    val accountId = resolveAccountInput(interaction.command.strings["account_id"]!!) { message ->
        response.respond { content = message }
    } ?: return
    val client = DeadlockClient()
    try {
        val result = RecentMatchLookup(gc, client::getRecentMatch, client::getMatchByMatchID).lookup(accountId, 1)
        val match = result.matches.firstOrNull()
        if (match == null) {
            response.respond {
                content = result.latestId?.let {
                    "Your latest match is `$it`, but its details aren't available yet. Please try again later."
                } ?: "No recent matches were found for this account."
            }
            return
        }
        val info = result.metadata[match.matchId] ?: client.getMatchByMatchID(match.matchId)
        val channel = requireNotNull(kord.getChannelOf<dev.kord.core.entity.channel.TextChannel>(interaction.channelId))
        MatchMessageGenerator.generateRecentMatch(match, interaction.user.globalName, channel, info)
        response.respond { content = "✅ Fetched match `${match.matchId}` for `$accountId`." }
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

private suspend fun handleLastFive(interaction: ChatInputCommandInteraction, kord: Kord, gc: MatchHistoryProvider?) {
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
        val result = RecentMatchLookup(gc, client::getRecentMatch, client::getMatchByMatchID).lookup(user.accountId, 5)
        val waiting = result.unavailableIds.takeIf { it.isNotEmpty() }?.joinToString { "`$it`" }
        val notice = waiting?.let { "Waiting for match details: $it. Some recent matches are not shown yet." }
        if (result.matches.isEmpty()) {
            response.respond { content = notice ?: "No recent matches were found for this account." }
            return
        }
        val channel = requireNotNull(kord.getChannelOf<dev.kord.core.entity.channel.TextChannel>(Snowflake(user.channelId.toString())))
        MatchMessageGenerator.generateLastFive(result.matches, user.discordUser, channel, notice)
        response.respond { content = notice ?: "✅ Fetched your recent matches." }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("Last five command failed: ${e.message}")
        response.respond { content = "I couldn't retrieve your recent matches right now. Please try again later." }
    } finally {
        client.close()
    }
}
