package bot

import api.gc.MatchHistoryProvider
import api.gc.SteamFriendship
import dev.kord.rest.builder.component.actionRow
import dev.kord.rest.builder.message.MessageBuilder
import kotlinx.coroutines.CancellationException
import models.MatchHistoryDTO

internal fun steamOnboarding(gc: MatchHistoryProvider?, account: String? = null): String {
    if (gc == null) return "Steam bot access is not configured on this deployment."
    val identity = gc.steamBotIdentity()
        ?: return "The Steam bot is connecting. Try `/steambot` again shortly."
    val status = account?.let { gc.friendship(it) }
    val instructions = when (status) {
        SteamFriendship.FRIEND -> "You're friends with the bot on Steam. If GC access is still denied, friendship alone hasn't granted access; check your Deadlock privacy settings."
        SteamFriendship.INCOMING -> "Your friend request is pending."
        SteamFriendship.OUTGOING -> "The bot has a pending request to you; accept it on Steam."
        SteamFriendship.BLOCKED -> "This friendship is blocked or ignored. Resolve that on Steam before sending a request."
        else -> "Open the bot's profile and select **Add Friend**, or enter its friend code in Steam → Friends → Add a Friend."
    }
    val acceptance = if (gc.autoAcceptFriendsEnabled())
        "Incoming requests are accepted automatically for registered accounts or within 15 minutes of a `/signup` attempt. Run `/signup` with your Steam profile URL before or after sending the request."
    else "Automatic acceptance is disabled; the bot owner must accept your request on Steam."
    return "$instructions\n[Open bot on Steam](${identity.profileUrl}) · Friend code: `${identity.friendCode}`\n$acceptance"
}

internal fun MessageBuilder.steamBotButton(gc: MatchHistoryProvider?) {
    gc?.steamBotIdentity()?.let { identity ->
        actionRow { linkButton(identity.profileUrl) { label = "Open bot on Steam" } }
    }
}

/** An authoritative GC response also verifies accounts without relying on the public API. */
internal suspend fun verifySignupAccount(
    account: String, gc: MatchHistoryProvider?, fallback: suspend (String) -> List<MatchHistoryDTO>?,
): List<MatchHistoryDTO>? {
    gc?.authorizeSignupFriendRequest(account)
    if (gc != null) {
        try { return gc.recentHistory(account).summaries }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { println("GC signup verification unavailable for $account: ${e.message}") }
    }
    return fallback(account)
}
