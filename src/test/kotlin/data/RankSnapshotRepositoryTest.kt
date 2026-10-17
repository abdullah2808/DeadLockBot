package data

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.*

class RankSnapshotRepositoryTest {
    @Test
    fun `snapshots are stored per account and replaced in place`() {
        Database.connect("jdbc:h2:mem:ranks-${System.nanoTime()};DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(RankSnapshotTable) }
        RankSnapshotRepository.save("1", RankSnapshot(54, 100))
        RankSnapshotRepository.save("2", RankSnapshot(null, 200))
        RankSnapshotRepository.save("1", RankSnapshot(55, 300))
        assertEquals(mapOf("1" to RankSnapshot(55, 300), "2" to RankSnapshot(null, 200)), RankSnapshotRepository.all())
    }
}
