package bot

import api.DeadlockClient
import dev.kord.core.Kord
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.on
import data.UserRepository
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.interaction.respondEphemeral
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.rest.builder.interaction.string
import util.MatchMessageGenerator

suspend fun registerCommands(kord: Kord) {
    kord.createGlobalChatInputCommand("signup", "Track your Deadlock matches") {
        string("account_id", "Your Deadlock account ID") { required = true }
    }

    kord.createGlobalChatInputCommand("unsubscribe", "Stop tracking your matches")

    kord.createGlobalChatInputCommand("recentmatch", "Get Your Most Recent Match") {
        string("account_id", "Your Deadlock account ID") { required = true }
    }

}

suspend fun handleCommands(kord: Kord) {
    kord.on<ChatInputCommandInteractionCreateEvent> {
        val interaction = interaction
        when (interaction.command.rootName) {
            "signup" -> handleSignup(interaction)
            "unsubscribe" -> handleUnsubscribe(interaction)
            "recentmatch" -> handleRecentMatch(interaction, kord)
        }
    }
}

private suspend fun handleSignup(interaction: ChatInputCommandInteraction) {
    val accountId = interaction.command.strings["account_id"]!!
    val discordId = interaction.user.id.toString()
    val channelId = interaction.channelId.toString()
    val discordUser = interaction.user.globalName.toString()
    println(discordId)
    println(accountId)
    println(channelId)

    UserRepository.addUser(discordId, accountId, channelId, discordUser)
    interaction.deferPublicResponse().respond {
        content = "✅ Registered your Deadlock account: `$accountId`."
    }
}

private suspend fun handleRecentMatch(interaction: ChatInputCommandInteraction, kord: Kord) {
    val accountId = interaction.command.strings["account_id"]!!
    val channelId = interaction.channelId
    val discordUser = interaction.user.globalName
    val client = DeadlockClient()
    val recentMatch = client.getRecentMatch(accountId)
    val channel = kord.getChannelOf<dev.kord.core.entity.channel.TextChannel>(
        channelId
    )
    val message = MatchMessageGenerator.generate(recentMatch, discordUser, channel)
    interaction.deferEphemeralResponse().respond {
        content = "✅ Fetched recent match for: `$accountId`."
    }
}

private suspend fun handleUnsubscribe(interaction: ChatInputCommandInteraction) {
    val discordId = interaction.user.id.toString()
    UserRepository.removeUser(discordId)
    interaction.deferPublicResponse().respond {
        content = "❌ You’ve been unsubscribed from match tracking."
    }
}