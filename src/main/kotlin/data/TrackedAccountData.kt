package data

import org.jetbrains.exposed.sql.transactions.transaction

enum class UnsubscribeOutcome { NOT_SIGNED_UP, DATA_DELETED, CLEANUP_PENDING }

/** Stored match data and rank badges follow tracking status: they exist only for accounts in `tracked_users`. */
object TrackedAccountData {
    /**
     * Tracking stops first, in its own transaction, so a failed cleanup never keeps anyone subscribed.
     * The cleanup is one transaction, and [sweepUntracked] retries it if it fails.
     */
    fun unsubscribe(discordId: String): UnsubscribeOutcome {
        val account = UserRepository.selectUserByDiscordID(discordId)?.accountId ?: return UnsubscribeOutcome.NOT_SIGNED_UP
        UserRepository.removeUser(discordId)
        return try {
            transaction {
                WeeklyDigestRepository.disable(discordId)
                WeeklyDigestRepository.deleteAccount(account)
                MatchCacheRepository.deleteAccount(account)
                RankSnapshotRepository.deleteAccount(account)
            }
            UnsubscribeOutcome.DATA_DELETED
        } catch (e: Exception) {
            println("Stored data cleanup failed for $account; the next sweep retries it: ${e.message}")
            UnsubscribeOutcome.CLEANUP_PENDING
        }
    }

    /** Removes what a failed cleanup, an in-flight save during unsubscribe, or an older bot version left behind. */
    fun sweepUntracked(): Unit = transaction {
        WeeklyDigestRepository.deleteUntracked()
        MatchCacheRepository.deleteUntracked()
        RankSnapshotRepository.deleteUntracked()
    }
}
