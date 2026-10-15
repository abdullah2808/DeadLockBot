package data

import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

object GcQueryTable : Table("gc_metadata_queries") {
    val sentAt = long("sent_at")
}

/** Persists when fresh GC metadata queries were sent, so restarts cannot overspend the rolling budget. */
object GcQueryLog {
    fun since(after: Long): List<Long> = transaction {
        GcQueryTable.selectAll().where { GcQueryTable.sentAt greater after }.map { it[GcQueryTable.sentAt] }
    }

    fun record(at: Long, pruneBefore: Long): Unit = transaction {
        GcQueryTable.insert { it[sentAt] = at }
        GcQueryTable.deleteWhere { sentAt less pruneBefore }
        Unit
    }
}
