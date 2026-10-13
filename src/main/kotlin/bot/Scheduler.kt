package bot

import api.DeadlockClient
import api.MatchMetadataService
import api.gc.MatchHistoryProvider
import data.UserRepository
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.channel.TextChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import models.toMatchHistory
import util.MatchMessageGenerator

/**
 * Match-centric tracking loop. Each cycle asks the [MatchSource] which tracked
 * accounts finished a match, then fetches that match's (fresh) metadata, maps the
 * player's row, and posts the summary embed — de-duping against the persisted
 * `last_match_id`. This replaces the previous per-user polling of the stale
 * per-player match-history endpoint.
 *
 * When [gcProvider] is supplied (Steam bot account configured), matches are
 * discovered via the authoritative Deadlock Game Coordinator ([GcMatchSource]);
 * otherwise it falls back to the top-150 active feed ([ActiveFeedMatchSource]).
 */
suspend fun startScheduler(kord: Kord, gcProvider: MatchHistoryProvider? = null, metadataService: MatchMetadataService? = null) {
    val client = DeadlockClient()
    val source: MatchSource = if (gcProvider != null) {
        println("Match source: Deadlock Game Coordinator.")
        GcMatchSource(gcProvider)
    } else {
        println("Match source: active feed (no Steam credentials configured).")
        ActiveFeedMatchSource(client)
    }

    // Matches awaiting a successful post (metadata not ready yet, or a transient
    // post error), with their attempt counts.
    val pending = mutableMapOf<FinishedMatch, Int>()
    val retries = MetadataRetrySchedule()
    // Poll every 90 seconds, but wake after a newly discovered match's 60-second grace period.
    val timing = NotificationTiming()

    try {
        while (true) {
            try {
                val users = UserRepository.getAllUsers()
                val usersByAccount = users.associateBy { it.accountId }
                gcProvider?.updateTrackedAccounts(usersByAccount.keys)

                if (timing.pollDue()) {
                    try {
                        source.pollFinished(usersByAccount.keys).forEach { finished ->
                            if (usersByAccount[finished.accountId]?.lastMatchId == finished.matchId.toString()) return@forEach
                            if (pending.putIfAbsent(finished, 0) == null) {
                                timing.discovered(finished)
                                println("Found match ${finished.matchId} for ${finished.accountId}; waiting 60s for detailed metadata before posting.")
                            }
                        }
                    } finally {
                        timing.pollFinished()
                    }
                }

                for (finished in pending.keys.toList()) {
                    val user = usersByAccount[finished.accountId]
                    if (user == null) {
                        pending.remove(finished)          // no longer tracked
                        retries.forget(finished)
                        timing.forget(finished)
                        continue
                    }
                    if (finished.matchId.toString() == user.lastMatchId) {
                        pending.remove(finished)          // already posted
                        retries.forget(finished)
                        timing.forget(finished)
                        continue
                    }

                    if (!timing.ready(finished) || !retries.ready(finished)) continue

                    val cachedSummary = gcProvider?.cachedMatch(finished.accountId, finished.matchId)

                    val metadata = try {
                        if (metadataService != null) metadataService.metadataFor(finished.accountId, finished.matchId)
                        else client.getMatchByMatchID(finished.matchId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        println("Metadata fetch error for match ${finished.matchId}: ${e.message}")
                        null
                    }
                    val match = cachedSummary ?: metadata?.toMatchHistory(finished.accountId)

                    if (match == null) {
                        val wait = deferPendingMetadata(finished, pending, retries)
                        println("Waiting for metadata for match ${finished.matchId}; next attempt in ${wait / 1000}s.")
                        continue
                    }
                    retries.forget(finished)

                    try {
                        deliverPendingMatch(
                            finished, pending,
                            resolveChannel = { user.channelId?.let { kord.getChannelOf<TextChannel>(Snowflake(it)) } },
                            send = { channel -> MatchMessageGenerator.generateRecentMatch(match, user.discordUser, channel, metadata) },
                            persist = { UserRepository.updateLastMatch(finished.accountId, finished.matchId.toString()) },
                        )
                        timing.forget(finished)
                        println("Posted match ${finished.matchId} for ${finished.accountId}.")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        println("Error posting match ${finished.matchId} for ${finished.accountId}: ${e.message}")
                        pending[finished] = (pending[finished] ?: 0) + 1
                    }
                }
                metadataService?.retryPending(usersByAccount.keys)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("Scheduler cycle error: ${e.message}")
                // Avoid a tight loop if loading users or polling fails before its timer advances.
                timing.pollFinished()
            }

            delay(timing.nextWakeDelayMillis())
        }
    } finally {
        client.close()
    }
}


/** Keep the match pending unless an accessible channel, send and persistence all succeed. */
internal suspend fun <C : Any> deliverPendingMatch(
    finished: FinishedMatch,
    pending: MutableMap<FinishedMatch, Int>,
    resolveChannel: suspend () -> C?,
    send: suspend (C) -> Unit,
    persist: () -> Unit,
) {
    val channel = requireNotNull(resolveChannel()) { "Notification channel is unavailable" }
    send(channel)
    persist()
    pending.remove(finished)
}
