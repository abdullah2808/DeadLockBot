package data

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.*

class RankSnapshotRepositoryTest {
    @Test
    fun `snapshots are stored per account and replaced in place`() {
        Database.connect("jdbc:h2:mem:ranks-${System.nanoTime()};DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(UserTable, RankSnapshotTable) }
        UserRepository.addUser("discord-1", "1", "channel", "Player 1")
        UserRepository.addUser("discord-2", "2", "channel", "Player 2")
        assertTrue(RankSnapshotRepository.saveIfTracked("1", RankSnapshot(54, 100)))
        assertTrue(RankSnapshotRepository.saveIfTracked("2", RankSnapshot(null, 200)))
        assertTrue(RankSnapshotRepository.saveIfTracked("1", RankSnapshot(55, 300)))
        assertEquals(mapOf("1" to RankSnapshot(55, 300), "2" to RankSnapshot(null, 200)), RankSnapshotRepository.all())
    }

    @Test
    fun `an untracked account's snapshot is not stored`() {
        Database.connect("jdbc:h2:mem:ranks-${System.nanoTime()};DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(UserTable, RankSnapshotTable) }
        assertFalse(RankSnapshotRepository.saveIfTracked("1", RankSnapshot(54, 100)))
        assertEquals(emptyMap(), RankSnapshotRepository.all())
    }
}
