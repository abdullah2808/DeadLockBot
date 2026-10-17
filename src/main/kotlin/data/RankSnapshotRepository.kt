package data

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert

/** Last ranked badge seen on each tracked player's profile card, so changes can be announced once. */
object RankSnapshotTable : Table("rank_snapshots") {
    val accountId = varchar("account_id", 50)
    /** tier * 10 + subrank; null while the player has no visible rank. */
    val badge = integer("badge").nullable()
    val checkedAt = long("checked_at")

    override val primaryKey = PrimaryKey(accountId)
}

data class RankSnapshot(val badge: Int?, val checkedAt: Long)

object RankSnapshotRepository {
    fun all(): Map<String, RankSnapshot> = transaction {
        RankSnapshotTable.selectAll().associate {
            it[RankSnapshotTable.accountId] to RankSnapshot(it[RankSnapshotTable.badge], it[RankSnapshotTable.checkedAt])
        }
    }

    fun save(accountId: String, snapshot: RankSnapshot): Unit = transaction {
        RankSnapshotTable.upsert {
            it[RankSnapshotTable.accountId] = accountId
            it[badge] = snapshot.badge
            it[checkedAt] = snapshot.checkedAt
        }
        Unit
    }
}
