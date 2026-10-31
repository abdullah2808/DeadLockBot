package bot

import api.DeadlockClient
import api.LiveMatchService
import api.MatchMetadataService
import api.gc.MatchHistoryProvider
import data.MatchNotificationRepository
import data.PostedNotification
import data.UserRepository
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.MessageBehavior
import dev.kord.core.entity.channel.TextChannel
import dev.kord.rest.request.RestRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import dev.kord.core.behavior.channel.createMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import models.RankRepository
import util.LiveMessageGenerator
import util.MatchModes
import models.MatchDTO
import models.MatchHistoryDTO
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
suspend fun startScheduler(kord: Kord, gcProvider: MatchHistoryProvider? = null, metadataService: MatchMetadataService? = null,
                           live: LiveMatchService? = null) {
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
    // Poll every 90 seconds. Metadata is fetched from discovery on; a notification posts as soon as it
    // arrives, or with the basic summary after 60 seconds and is edited once metadata arrives.
    val timing = NotificationTiming()
    // Rank changes come from GC profile cards, so they are only watched when a Steam session exists.
    val ranks = gcProvider?.let { gc -> RankTracker({ gc.profileCard(it) }) }

    try {
        while (true) {
            try {
                val users = UserRepository.getAllUsers()
                val usersByAccount = users.associateBy { it.accountId }
                TrackingRuntime.retain(usersByAccount.keys)
                gcProvider?.updateTrackedAccounts(usersByAccount.keys)

                val pollDue = timing.pollDue()
                if (pollDue) {
                    try {
                        source.pollFinished(usersByAccount.keys).forEach { finished ->
                            if (usersByAccount[finished.accountId]?.lastMatchId == finished.matchId.toString()) return@forEach
                            if (pending.putIfAbsent(finished, 0) == null) {
                                timing.discovered(finished)
                                println("Found match ${finished.matchId} for ${finished.accountId}; fetching metadata, posting within 60s.")
                            }
                        }
                    } finally {
                        timing.pollFinished()
                    }
                }

                TrackingRuntime.pending(pending.keys)
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

                    if (!retries.ready(finished)) continue
                    val deadlinePassed = timing.ready(finished)
                    // Before the deadline only the metadata service's own (quick) retry schedule applies.
                    if (metadataService == null && !deadlinePassed) continue

                    val metadata = try {
                        if (metadataService != null) metadataService.metadataFor(finished.accountId, finished.matchId)
                        else client.getMatchByMatchID(finished.matchId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        println("Metadata fetch error for match ${finished.matchId}: ${e.message}")
                        null
                    }
                    if (metadata == null && !deadlinePassed) continue

                    val cachedSummary = gcProvider?.cachedMatch(finished.accountId, finished.matchId)
                    val match = cachedSummary ?: metadata?.toMatchHistory(finished.accountId)

                    if (match == null) {
                        val wait = deferPendingMetadata(finished, pending, retries)
                        println("Waiting for metadata for match ${finished.matchId}; next attempt in ${wait / 1000}s.")
                        continue
                    }
                    retries.forget(finished)
                    data.WeeklyDigestRepository.record(finished.accountId, listOf(match))

                    try {
                        deliverPendingMatch(
                            finished, pending,
                            resolveChannel = { user.channelId?.let { kord.getChannelOf<TextChannel>(Snowflake(it)) } },
                            send = { channel ->
                                val message = MatchMessageGenerator.generateRecentMatch(match, user.discordUser, channel, metadata)
                                if (metadata == null && message != null) awaitMetadata(PostedNotification(finished.accountId,
                                    finished.matchId, message.channelId.toString(), message.id.toString(), System.currentTimeMillis()))
                            },
                            persist = { UserRepository.updateLastMatch(finished.accountId, finished.matchId.toString()) },
                        )
                        println("Posted match ${finished.matchId} for ${finished.accountId} " +
                            "${if (metadata != null) "with" else "without"} full metadata, ${timing.sinceDiscoveryMillis(finished) / 1000}s after discovery.")
                        timing.forget(finished)
                        if (MatchModes.affectsRank(match.gameMode, match.matchMode)) ranks?.rankedMatchFinished(finished.accountId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        println("Error posting match ${finished.matchId} for ${finished.accountId}: ${e.message}")
                        pending[finished] = (pending[finished] ?: 0) + 1
                    }
                }
                TrackingRuntime.pending(pending.keys)
                metadataService?.retryPending(usersByAccount.keys)
                if (metadataService != null) enrichPostedNotifications(
                    awaiting = {
                        val since = System.currentTimeMillis() - ENRICHMENT_WINDOW_MILLIS
                        MatchNotificationRepository.prune(since) // older posts are no longer worth editing
                        MatchNotificationRepository.awaitingMetadata(since)
                    },
                    lookup = { metadataService.metadataFor(it.accountId, it.matchId) },
                    summary = { notification, metadata ->
                        gcProvider?.cachedMatch(notification.accountId, notification.matchId) ?: metadata.toMatchHistory(notification.accountId)
                    },
                    edit = { notification, summary, metadata ->
                        val user = usersByAccount[notification.accountId] ?: run {
                            MatchNotificationRepository.remove(notification.accountId, notification.matchId) // unsubscribed
                            return@enrichPostedNotifications false
                        }
                        val message = MessageBehavior(Snowflake(notification.channelId), Snowflake(notification.messageId), kord)
                        try {
                            MatchMessageGenerator.updateRecentMatch(message, summary, user.discordUser, metadata)
                            true
                        } catch (e: RestRequestException) {
                            // Deleted messages and lost channel access are permanent; other failures retry next cycle.
                            if (e.status.code !in setOf(403, 404)) throw e
                            println("Notification for match ${notification.matchId} can no longer be edited (HTTP ${e.status.code}).")
                            MatchNotificationRepository.remove(notification.accountId, notification.matchId)
                            false
                        }
                    },
                    remove = { MatchNotificationRepository.remove(it.accountId, it.matchId) },
                )
                sendWeeklyDigests(kord, users)
                if (pollDue && live != null) announceLiveMatches(kord, users, live)
                ranks?.checkDue(usersByAccount.keys)?.forEach { (account, change) ->
                    val user = usersByAccount[account] ?: return@forEach
                    announceRankChange(kord, user.channelId, user.discordUser, change)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("Scheduler cycle error: ${e.message}")
                // Avoid a tight loop if loading users or polling fails before its timer advances.
                timing.pollFinished()
            }

            // Wake for the metadata service's quick retries too, not only for source polls and deadlines.
            val retryIn = metadataService?.nextRetryInMillis()?.coerceAtLeast(MIN_WAKE_MILLIS)
            delay(minOf(timing.nextWakeDelayMillis(), retryIn ?: Long.MAX_VALUE))
        }
    } finally {
        client.close()
    }
}


private const val ENRICHMENT_WINDOW_MILLIS = 24 * 60 * 60_000L
private const val MIN_WAKE_MILLIS = 5_000L

/** Best effort: the new badge is already recorded, so a failed post is not retried (and never repeated). */
private suspend fun announceRankChange(kord: Kord, channelId: String?, playerName: String, change: RankChange) {
    try {
        val channel = channelId?.let { kord.getChannelOf<TextChannel>(Snowflake(it)) } ?: return
        val (previous, current) = withContext(Dispatchers.IO) {
            change.previous?.let(RankRepository::describeBadge) to RankRepository.describeBadge(change.current)
        }
        channel.createMessage { embeds = mutableListOf(LiveMessageGenerator.rankChangeEmbed(playerName, change, previous, current)) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("Could not announce rank change for $playerName: ${e.message}")
    }
}

/** A failure to remember a notification must never fail (and so duplicate) its delivery. */
private fun awaitMetadata(notification: PostedNotification) {
    try { MatchNotificationRepository.add(notification) }
    catch (e: Exception) { println("Could not queue notification for match ${notification.matchId} for enrichment: ${e.message}") }
}

/**
 * Edit notifications that were posted with only the basic summary once full metadata is available.
 * Lookups follow the metadata service's retry schedule, so calling this every cycle is cheap.
 */
internal suspend fun enrichPostedNotifications(
    awaiting: () -> List<PostedNotification>,
    lookup: suspend (PostedNotification) -> MatchDTO?,
    summary: suspend (PostedNotification, MatchDTO) -> MatchHistoryDTO?,
    edit: suspend (PostedNotification, MatchHistoryDTO, MatchDTO) -> Boolean,
    remove: (PostedNotification) -> Unit,
) {
    val queued = try { awaiting() } catch (e: Exception) {
        println("Could not load notifications awaiting metadata: ${e.message}")
        return
    }
    for (notification in queued) {
        try {
            val metadata = lookup(notification) ?: continue
            val match = summary(notification, metadata) ?: continue
            if (edit(notification, match, metadata)) {
                remove(notification)
                println("Added full metadata to the notification for match ${notification.matchId} " +
                    "(${notification.accountId}), ${(System.currentTimeMillis() - notification.postedAt) / 1000}s after posting.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Could not enrich the notification for match ${notification.matchId}: ${e.message}")
        }
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
