package bot

import api.DeadlockClient
import data.UserRepository
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.channel.TextChannel
import kotlinx.coroutines.delay
import models.toMatchHistory
import util.MatchMessageGenerator

// Poll cadence. The active feed is CDN-cached ~120s, so polling faster gains no
// fresher data; ~90s keeps detection prompt while the pending-metadata retry
// benefits from a slightly shorter loop.
private const val POLL_INTERVAL_MS = 90_000L

// A just-finished match's metadata can lag a little behind the active feed, so we
// retry a bounded number of times before giving up on it.
private const val MAX_METADATA_ATTEMPTS = 5

/**
 * Match-centric tracking loop. Each cycle asks the [MatchSource] which tracked
 * accounts finished a match, then fetches that match's (fresh) metadata, maps the
 * player's row, and posts the summary embed — de-duping against the persisted
 * `last_match_id`. This replaces the previous per-user polling of the stale
 * per-player match-history endpoint.
 */
suspend fun startScheduler(kord: Kord) {
    val client = DeadlockClient()
    val source: MatchSource = ActiveFeedMatchSource(client)

    // Matches awaiting a successful post (metadata not ready yet, or a transient
    // post error), with their attempt counts.
    val pending = mutableMapOf<FinishedMatch, Int>()

    while (true) {
        try {
            val users = UserRepository.getAllUsers()
            val usersByAccount = users.associateBy { it.accountId }

            source.pollFinished(usersByAccount.keys).forEach { pending.putIfAbsent(it, 0) }

            for (finished in pending.keys.toList()) {
                val user = usersByAccount[finished.accountId]
                if (user == null) {
                    pending.remove(finished)          // no longer tracked
                    continue
                }
                if (finished.matchId.toString() == user.lastMatchId) {
                    pending.remove(finished)          // already posted
                    continue
                }

                val metadata = try {
                    client.getMatchByMatchID(finished.matchId)
                } catch (e: Exception) {
                    println("Metadata fetch error for match ${finished.matchId}: ${e.message}")
                    null
                }
                val match = metadata?.toMatchHistory(finished.accountId)

                if (metadata == null || match == null) {
                    val attempts = (pending[finished] ?: 0) + 1
                    if (attempts >= MAX_METADATA_ATTEMPTS) {
                        println("Giving up on match ${finished.matchId} for ${finished.accountId} after $attempts attempts.")
                        pending.remove(finished)
                    } else {
                        pending[finished] = attempts
                    }
                    continue
                }

                try {
                    UserRepository.updateLastMatch(finished.accountId, finished.matchId.toString())
                    val channel = user.channelId?.let { kord.getChannelOf<TextChannel>(Snowflake(it)) }
                    MatchMessageGenerator.generateRecentMatch(match, user.discordUser, channel, metadata)
                    pending.remove(finished)
                    println("Posted match ${finished.matchId} for ${finished.accountId}.")
                } catch (e: Exception) {
                    println("Error posting match ${finished.matchId} for ${finished.accountId}: ${e.message}")
                    pending[finished] = (pending[finished] ?: 0) + 1
                }
            }
        } catch (e: Exception) {
            println("Scheduler cycle error: ${e.message}")
        }

        delay(POLL_INTERVAL_MS)
    }
}
