package data

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {
    fun init() {
        Database.connect(
                url = Env.DB_URL,
                driver = "org.postgresql.Driver",
                user = Env.DB_USER,
                password = Env.DB_PASSWORD
        )
        transaction {
            SchemaUtils.create(UserTable)
        }
    }
}