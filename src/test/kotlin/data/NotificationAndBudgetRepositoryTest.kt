package data

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.*

class NotificationAndBudgetRepositoryTest {
    private fun setup() {
        Database.connect("jdbc:h2:mem:notify-${System.nanoTime()};DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(MatchNotificationTable, GcQueryTable) }
    }

    @Test fun `notifications awaiting metadata are newest first, replaced on repost and pruned`() {
        setup()
        MatchNotificationRepository.add(PostedNotification("1", 99, "10", "20", 1_000))
        MatchNotificationRepository.add(PostedNotification("2", 99, "10", "21", 2_000))
        MatchNotificationRepository.add(PostedNotification("1", 99, "10", "22", 3_000))
        assertEquals(listOf("22", "21"), MatchNotificationRepository.awaitingMetadata(0).map { it.messageId })
        assertEquals(listOf("22"), MatchNotificationRepository.awaitingMetadata(2_000).map { it.messageId })
        MatchNotificationRepository.remove("1", 99)
        assertEquals(listOf("21"), MatchNotificationRepository.awaitingMetadata(0).map { it.messageId })
        MatchNotificationRepository.prune(2_000)
        assertTrue(MatchNotificationRepository.awaitingMetadata(0).isEmpty())
    }

    @Test fun `GC query history keeps the rolling window and prunes older entries`() {
        setup()
        GcQueryLog.record(100, pruneBefore = 0)
        GcQueryLog.record(200, pruneBefore = 0)
        GcQueryLog.record(300, pruneBefore = 150)
        assertEquals(listOf(200L, 300L), GcQueryLog.since(0).sorted())
        assertEquals(listOf(300L), GcQueryLog.since(200))
    }
}
