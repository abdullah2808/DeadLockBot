package data


import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

object UserRepository {
    fun addUser(discordId: String, accountId: String) = transaction {
        if (UserTable.selectAll().where { UserTable.discordId eq discordId }.empty()) {
            UserTable.insert {
                it[UserTable.discordId] = discordId
                it[UserTable.accountId] = accountId
            }
        }
    }

    fun removeUser(discordId: String) = transaction {
        UserTable.deleteWhere() { UserTable.discordId eq discordId }
    }

    fun updateLastMatch(accountId: String, matchId: String) = transaction {
        UserTable.update({ UserTable.accountId eq accountId }) {
            it[lastMatchId] = matchId
        }
    }
}