package data

import org.jetbrains.exposed.sql.Table

object UserTable : Table("tracked_users") {
    val accountId = varchar("account_id", 50)
    val discordId = varchar("discord_id", 50)
    val lastMatchId = varchar("last_match_id", 50).nullable()
    val channelId = varchar("channel_id", 50)
    val discordUser = varchar("discord_user", 50)

    override val primaryKey = PrimaryKey(accountId)
}