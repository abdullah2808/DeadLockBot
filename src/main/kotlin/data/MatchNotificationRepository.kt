package data

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert

object MatchNotificationTable : Table("match_notifications") {
    val accountId = varchar("account_id", 50)
    val matchId = long("match_id")
    val channelId = varchar("channel_id", 32)
    val messageId = varchar("message_id", 32)
    val postedAt = long("posted_at")
    override val primaryKey = PrimaryKey(accountId, matchId)
}

data class PostedNotification(val accountId: String, val matchId: Long, val channelId: String, val messageId: String, val postedAt: Long)

/** Notifications posted before full metadata was available, kept (also across restarts) until they are edited. */
object MatchNotificationRepository {
    fun awaitingMetadata(postedAfter: Long, limit: Int = 10): List<PostedNotification> = transaction {
        MatchNotificationTable.selectAll().where { MatchNotificationTable.postedAt greater postedAfter }
            .orderBy(MatchNotificationTable.postedAt to SortOrder.DESC).limit(limit).map {
                PostedNotification(it[MatchNotificationTable.accountId], it[MatchNotificationTable.matchId],
                    it[MatchNotificationTable.channelId], it[MatchNotificationTable.messageId], it[MatchNotificationTable.postedAt])
            }
    }

    fun add(notification: PostedNotification): Unit = transaction {
        MatchNotificationTable.upsert {
            it[accountId] = notification.accountId
            it[matchId] = notification.matchId
            it[channelId] = notification.channelId
            it[messageId] = notification.messageId
            it[postedAt] = notification.postedAt
        }
        Unit
    }

    fun remove(account: String, match: Long): Unit = transaction {
        MatchNotificationTable.deleteWhere { (accountId eq account) and (matchId eq match) }
        Unit
    }

    fun prune(postedBefore: Long): Unit = transaction {
        MatchNotificationTable.deleteWhere { postedAt lessEq postedBefore }
        Unit
    }
}
