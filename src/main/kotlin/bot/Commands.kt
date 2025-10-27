package bot

import dev.kord.core.Kord
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.on
import data.UserRepository

suspend fun registerCommands(kord: Kord) {
    kord.createGlobalChatInputCommand("signup", "Track your Deadlock matches") {
        string("account_id", "Your Deadlock account ID") { required = true }
    }

    kord.createGlobalChatInputCommand("unsubscribe", "Stop tracking your matches")
}

suspend fun handleCommands(kord: Kord) {
    kord.on<ChatInputCommandInteractionCreateEvent> {
        val interaction = interaction
        when (interaction.command.rootName) {
            "signup" -> handleSignup(interaction)
            "unsubscribe" -> handleUnsubscribe(interaction)
        }
    }
}

private suspend fun handleSignup(interaction: ChatInputCommandInteraction) {
    val accountId = interaction.command.strings["account_id"]!!
    val discordId = interaction.user.id.toString()

    UserRepository.addUser(discordId, accountId)
    interaction.deferPublicResponse().respond {
        content = "✅ Registered your Deadlock account: `$accountId`."
    }
}

private suspend fun handleUnsubscribe(interaction: ChatInputCommandInteraction) {
    val discordId = interaction.user.id.toString()
    UserRepository.removeUser(discordId)
    interaction.deferPublicResponse().respond {
        content = "❌ You’ve been unsubscribed from match tracking."
    }
}