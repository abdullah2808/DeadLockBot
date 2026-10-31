package data

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert

object LiveAlertChannelTable : Table("live_alert_channels") {
    val channelId = varchar("channel_id", 32)
    val enabledBy = varchar("enabled_by", 32)
    val enabledAt = long("enabled_at")
    override val primaryKey = PrimaryKey(channelId)
}

object LiveAlertPostTable : Table("live_alert_posts") {
    val channelId = varchar("channel_id", 32)
    val matchId = long("match_id")
    val postedAt = long("posted_at")
    override val primaryKey = PrimaryKey(channelId, matchId)
}

/** Channels that opted into live match alerts, and the matches already announced there (also across restarts). */
object LiveAlertRepository {
    fun enable(channel: String, by: String, now: Long = System.currentTimeMillis()): Unit = transaction {
        LiveAlertChannelTable.upsert(onUpdateExclude = listOf(LiveAlertChannelTable.enabledAt, LiveAlertChannelTable.enabledBy)) {
            it[channelId] = channel; it[enabledBy] = by; it[enabledAt] = now
        }
        Unit
    }

    fun disable(channel: String): Unit = transaction {
        LiveAlertChannelTable.deleteWhere { channelId eq channel }
        Unit
    }

    fun isEnabled(channel: String): Boolean = transaction {
        !LiveAlertChannelTable.selectAll().where { LiveAlertChannelTable.channelId eq channel }.empty()
    }

    fun enabledChannels(): Set<String> = transaction {
        LiveAlertChannelTable.selectAll().map { it[LiveAlertChannelTable.channelId] }.toSet()
    }

    fun announced(channels: Set<String>): Set<Pair<String, Long>> = if (channels.isEmpty()) emptySet() else transaction {
        LiveAlertPostTable.selectAll().where { LiveAlertPostTable.channelId inList channels }
            .map { it[LiveAlertPostTable.channelId] to it[LiveAlertPostTable.matchId] }.toSet()
    }

    fun claim(channel: String, match: Long, now: Long = System.currentTimeMillis()): Unit = transaction {
        LiveAlertPostTable.upsert { it[channelId] = channel; it[matchId] = match; it[postedAt] = now }
        Unit
    }

    fun release(channel: String, match: Long): Unit = transaction {
        LiveAlertPostTable.deleteWhere { (channelId eq channel) and (matchId eq match) }
        Unit
    }

    fun prune(postedBefore: Long): Unit = transaction {
        LiveAlertPostTable.deleteWhere { postedAt lessEq postedBefore }
        Unit
    }
}
