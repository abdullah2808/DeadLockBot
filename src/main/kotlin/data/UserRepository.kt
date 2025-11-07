package data


import data.UserRepository.UserRecord
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

object UserRepository {
    data class UserRecord(
        val discordId: String,
        val accountId: String,
        val discordUser: String,
        val lastMatchId: String?,
        val channelId: String?
    )

    fun addUser(discordId: String, accountId: String, channelId: String, discordUser: String) = transaction {
        if (UserTable.selectAll().where { UserTable.discordId eq discordId }.empty()) {
            UserTable.insert {
                it[UserTable.discordId] = discordId
                it[UserTable.accountId] = accountId
                it[UserTable.discordUser] = discordUser
                it[UserTable.channelId] = channelId
            }
        }
    }

    fun removeUser(discordId: String) = transaction {
        UserTable.deleteWhere { UserTable.discordId eq discordId }
    }

    fun getAllUsers(): List<UserRecord> = transaction {
        UserTable.selectAll().map {
            UserRecord(
                discordId = it[UserTable.discordId],
                accountId = it[UserTable.accountId],
                discordUser = it[UserTable.discordUser],
                lastMatchId = it[UserTable.lastMatchId],
                channelId = it[UserTable.channelId]
            )
        }
    }

    fun selectUserByDiscordID(discordId: String): UserRecord? = transaction {
        UserTable
            .selectAll()
            .where { UserTable.discordId eq discordId }
            .map {
                UserRecord(
                    discordId = it[UserTable.discordId],
                    accountId = it[UserTable.accountId],
                    discordUser = it[UserTable.discordUser],
                    lastMatchId = it[UserTable.lastMatchId],
                    channelId = it[UserTable.channelId]
                )
            }
            .singleOrNull()
    }

    fun updateLastMatch(accountId: String, matchId: String) = transaction {
        UserTable.update({ UserTable.accountId eq accountId }) {
            it[lastMatchId] = matchId
        }
    }
}