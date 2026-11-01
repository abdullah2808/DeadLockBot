package bot

import api.MatchMetadataService
import api.gc.GcTrackingDiagnostics
import api.gc.MatchHistoryProvider
import data.MatchNotificationTable
import data.UserRepository
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.core.behavior.interaction.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal object TrackingRuntime {
    data class Sync(val at: Long? = null, val issue: String? = null)
    private val syncs = mutableMapOf<String, Sync>()
    private var waiting = emptyMap<String, Int>()
    private val deliveryProblems = mutableMapOf<String, ChannelProblem>()
    @Synchronized fun deliveryProblem(account: String, problem: ChannelProblem?) {
        if (problem == null) deliveryProblems.remove(account) else deliveryProblems[account] = problem
    }
    @Synchronized fun deliveryProblem(account: String) = deliveryProblems[account]
    @Synchronized fun synced(account: String, at: Long = System.currentTimeMillis()) { syncs[account] = Sync(at) }
    @Synchronized fun failed(account: String, issue: String) { syncs[account] = Sync(syncs[account]?.at, issue) }
    @Synchronized fun get(account: String) = syncs[account] ?: Sync()
    @Synchronized fun retain(accounts: Set<String>) { syncs.keys.retainAll(accounts); deliveryProblems.keys.retainAll(accounts); waiting = waiting.filterKeys { it in accounts } }
    @Synchronized fun pending(matches: Collection<FinishedMatch>) { waiting = matches.groupingBy { it.accountId }.eachCount() }
    @Synchronized fun pendingCount(account: String) = waiting[account] ?: 0
}

internal fun trackingStatusText(sync: TrackingRuntime.Sync, gc: GcTrackingDiagnostics?, gcConfigured: Boolean,
    lastMatch: String?, unsent: Int, enrichment: Long, queue: Pair<Int, Long?>?,
    channelId: String? = null, deliveryProblem: ChannelProblem? = null): String = buildString {
    fun wait(ms: Long) = if (ms <= 0) "eligible now" else "in about ${(ms + 999) / 1000}s"
    append("**Your tracking status**\nSource: ${if (gcConfigured) "Steam / Deadlock GC" else "Public active feed (partial coverage)"}")
    append("\nLast successful source sync: ${sync.at?.let { "<t:${it / 1000}:R>" } ?: "No successful sync observed since this bot started"}")
    sync.issue?.let { append("\nLatest source issue: $it") }
    channelId?.toULongOrNull()?.let { append("\nNotification channel: <#$it>") }
    deliveryProblem?.let { append("\n⚠️ Latest delivery problem: ${channelProblemMessage(channelId, it)} $MOVE_TRACKING_HINT") }
    append("\nLast delivered match: ${lastMatch ?: "None recorded"}")
    append("\nNotifications waiting to send: $unsent\nPosted recaps waiting for full details: $enrichment")
    if (queue == null) append("\nMetadata retry queue: busy or unavailable; try again shortly")
    else append("\nMetadata retries: ${queue.first}${queue.second?.let { " · next check ${wait(it)}" } ?: ""}")
    gc?.let {
        append("\nSteam session: ${if (it.ready) "ready" else "connecting / unavailable"}")
        append("\nShared Steam cooldown: ${wait(it.sessionCooldownMillis)}")
        append("\nFresh metadata query: ${wait(maxOf(it.metadataWaitMillis, it.sessionCooldownMillis))}")
        append("\nTracked metadata budget remaining: ${it.metadataRemaining}")
    }
    append("\n\nRead-only snapshot. Retry times are estimates, not promises that Valve has published the report. Source sync and unsent counts reset on restart; posted enrichment survives restarts. Use `/match` to recheck a specific report or `/steambot` for access help.")
}

internal suspend fun handleTrackingStatus(interaction: ChatInputCommandInteraction, gc: MatchHistoryProvider?, metadata: MatchMetadataService?) {
    val response = interaction.deferEphemeralResponse()
    try {
        val text = withContext(Dispatchers.IO) {
            val user = UserRepository.selectUserByDiscordID(interaction.user.id.toString())
                ?: return@withContext "Use `/signup` to link your account first."
            val posted = transaction {
                MatchNotificationTable.selectAll().where { (MatchNotificationTable.accountId eq user.accountId) and
                    (MatchNotificationTable.postedAt greater (System.currentTimeMillis() - 24 * 60 * 60_000L)) }.count()
            }
            trackingStatusText(TrackingRuntime.get(user.accountId), gc?.trackingDiagnostics(), gc != null,
                user.lastMatchId, TrackingRuntime.pendingCount(user.accountId), posted, metadata?.pendingStatus(user.accountId),
                user.channelId, TrackingRuntime.deliveryProblem(user.accountId))
        }
        response.respond { content = text }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) { response.respond { content = "Tracking status is temporarily unavailable. Please try again shortly." } }
}
