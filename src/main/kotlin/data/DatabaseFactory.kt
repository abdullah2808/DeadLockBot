package data

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.net.URI

object DatabaseFactory {
    fun init() {
        val dbUrl = Env.DB_URL ?: error("DATABASE_URL not set")
        val uri = URI(dbUrl)
        val jdbcUrl = "jdbc:postgresql://${uri.host}:${uri.port}${uri.path}"
        Database.connect(
                url = jdbcUrl,
                driver = "org.postgresql.Driver",
                user = Env.DB_USER,
                password = Env.DB_PASSWORD
        )
        transaction {
            SchemaUtils.create(UserTable)
        }
    }
}