package bot

import dev.kord.common.entity.ChannelType
import dev.kord.common.entity.Permission
import dev.kord.common.entity.Permissions
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.rest.json.JsonErrorCode
import dev.kord.rest.request.RestRequestException
import kotlinx.coroutines.CancellationException

/** Why a channel can't take the bot's posts. Each case has its own user-facing explanation. */
internal enum class ChannelProblem { NO_ACCESS, MISSING, ARCHIVED_THREAD, NOT_MESSAGEABLE }

internal class ChannelUnavailableException(val problem: ChannelProblem) : Exception("Notification channel is unavailable: $problem")

/**
 * A handle for posting to a stored channel. Slash commands only run in channels that take messages, so the
 * channel is not fetched first: Kord's `getChannelOf<TextChannel>` returns null for threads, announcement and
 * voice channels, and its REST decoder rejects the `application_id: null` voice channels can carry
 * (see [acceptNullableChannelApplicationIds]). Discord's answer to the post is the authority; see [channelProblem].
 */
internal fun deliveryChannel(kord: Kord, storedId: String?): MessageChannelBehavior =
    storedId?.toULongOrNull()?.let { MessageChannelBehavior(Snowflake(it), kord) }
        ?: throw ChannelUnavailableException(ChannelProblem.MISSING)

/** The channel-level cause of a failed post, or null when the failure says nothing about the channel. */
internal fun channelProblem(error: Throwable): ChannelProblem? {
    if (error is ChannelUnavailableException) return error.problem
    if (error !is RestRequestException) return null
    val code = error.error?.code
    return when {
        code == JsonErrorCode.OperationOnArchivedThread || code == JsonErrorCode.TheadLocked -> ChannelProblem.ARCHIVED_THREAD
        code == JsonErrorCode.CannotSendMessagesInNonTextChannel || code == JsonErrorCode.CannotSendMessageInForumChannel ->
            ChannelProblem.NOT_MESSAGEABLE
        error.status.code == 403 -> ChannelProblem.NO_ACCESS
        // A 404 can also mean an expired interaction token, which is not the channel's fault.
        error.status.code == 404 && code == JsonErrorCode.UnknownChannel -> ChannelProblem.MISSING
        else -> null
    }
}

internal fun channelProblemMessage(channelId: String?, problem: ChannelProblem): String {
    val channel = channelId?.toULongOrNull()?.let { "<#$it>" } ?: "that channel"
    return when (problem) {
        ChannelProblem.NO_ACCESS -> "I don't have permission to post in $channel. I need View Channel, Send Messages " +
            "(Send Messages in Threads for a thread) and Embed Links there, and a private thread must add me."
        ChannelProblem.MISSING -> "I can't find $channel. It may have been deleted, or I may have been removed from its server."
        ChannelProblem.ARCHIVED_THREAD -> "$channel is an archived or locked thread, so I can't post there until it's reopened."
        ChannelProblem.NOT_MESSAGEABLE -> "$channel doesn't accept messages directly."
    }
}

internal const val MOVE_TRACKING_HINT = "To move tracking, use `/unsubscribe` and then `/signup` in a channel I can post in."

/** What `/status` shows for an account: set by a failed post, cleared by the next successful one. */
internal suspend fun <T> trackingDelivery(accounts: Collection<String>, post: suspend () -> T): T {
    val result = try {
        post()
    } catch (e: Exception) {
        channelProblem(e)?.let { problem -> accounts.forEach { TrackingRuntime.deliveryProblem(it, problem) } }
        throw e
    }
    accounts.forEach { TrackingRuntime.deliveryProblem(it, null) }
    return result
}

private val THREAD_TYPES = setOf(ChannelType.PublicGuildThread, ChannelType.PublicNewsThread, ChannelType.PrivateThread)

/** [channelType] is null when the interaction didn't say; either send permission is then given the benefit of the doubt. */
internal fun canPost(granted: Permissions, channelType: ChannelType?): Boolean {
    val canSend = when {
        channelType == null -> Permission.SendMessages in granted || Permission.SendMessagesInThreads in granted
        channelType in THREAD_TYPES -> Permission.SendMessagesInThreads in granted
        else -> Permission.SendMessages in granted
    }
    return canSend && Permission.ViewChannel in granted && Permission.EmbedLinks in granted
}

/**
 * Catches channels that would accept a signup and then never receive a notification: interactions still work
 * where the bot can't post. [appPermissions] is the interaction's own snapshot, null outside a guild. The fetch
 * only adds access problems permissions don't show, such as a private thread the bot isn't in; a failure that
 * says nothing about the channel must not block the signup.
 */
internal suspend fun signupChannelProblem(kord: Kord, channelId: Snowflake, appPermissions: Permissions?, channelType: ChannelType?): ChannelProblem? {
    if (appPermissions != null && !canPost(appPermissions, channelType)) return ChannelProblem.NO_ACCESS
    return try {
        kord.rest.channel.getChannel(channelId)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        channelProblem(e).also { if (it == null) println("Signup channel check for $channelId was inconclusive: ${e.message}") }
    }
}
