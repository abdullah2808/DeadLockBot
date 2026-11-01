package data

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
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
            SchemaUtils.create(WeeklySettingsTable, WeeklyMatchTable, WeeklyDeliveryTable, UserTable, CachedMatchTable, CachedPlayerMatchTable, GcQueryTable, MatchNotificationTable, RankSnapshotTable)
            addRankColumns()
        }
    }

    /** `SchemaUtils.create` skips tables that already exist, so deployed databases need these added explicitly. */
    internal fun Transaction.addRankColumns() {
        listOf("ranked_display_badge INT", "ranked_delta INT", "ranked_calibration_match INT", "ranked_used_demotion_protection BOOLEAN")
            .forEach { exec("ALTER TABLE cached_player_matches ADD COLUMN IF NOT EXISTS $it") }
    }
}
