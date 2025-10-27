package data

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {
    fun init() {
        Database.connect("", driver = "org.sqllite.jdbc")
        transaction {
            SchemaUtils.create(UserTable)
        }
    }
}