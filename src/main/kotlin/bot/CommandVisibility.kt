package bot

import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.rest.builder.interaction.ChatInputCreateBuilder
import dev.kord.rest.builder.interaction.boolean

/** Discord fixes a response's visibility when it is deferred, so this must be read before any work starts. */
private const val PUBLIC_OPTION = "public"

/** Must follow a command's required options: Discord rejects a required option after an optional one. */
internal fun ChatInputCreateBuilder.publicOption() =
    boolean(PUBLIC_OPTION, "Post the response in this channel for everyone (default: only you see it)")

internal fun ChatInputCommandInteraction.requestedPublic() = command.booleans[PUBLIC_OPTION] == true

/** Replies are private unless the caller opted in, including any error the command ends up reporting. */
internal suspend fun ChatInputCommandInteraction.deferWithVisibility(public: Boolean = requestedPublic()) =
    if (public) deferPublicResponse() else deferEphemeralResponse()
