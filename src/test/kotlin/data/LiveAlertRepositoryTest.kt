package data

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.*

class LiveAlertRepositoryTest {
    @BeforeTest fun connect() {
        Database.connect("jdbc:h2:mem:live-alerts-${System.nanoTime()};DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(LiveAlertChannelTable, LiveAlertPostTable) }
    }

    @Test fun `alerts are off until a channel enables them, and re-enabling is harmless`() {
        assertFalse(LiveAlertRepository.isEnabled("a"))
        assertTrue(LiveAlertRepository.enabledChannels().isEmpty())
        LiveAlertRepository.enable("a", "d1")
        LiveAlertRepository.enable("a", "d2")
        assertTrue(LiveAlertRepository.isEnabled("a"))
        assertEquals(setOf("a"), LiveAlertRepository.enabledChannels())
        LiveAlertRepository.disable("a")
        assertFalse(LiveAlertRepository.isEnabled("a"))
    }

    @Test fun `announced matches are per channel and survive turning alerts off and on`() {
        LiveAlertRepository.enable("a", "d1")
        LiveAlertRepository.claim("a", 10)
        LiveAlertRepository.claim("a", 10)
        LiveAlertRepository.claim("b", 11)
        LiveAlertRepository.disable("a")
        LiveAlertRepository.enable("a", "d1")
        assertEquals(setOf("a" to 10L), LiveAlertRepository.announced(setOf("a")))
        assertEquals(setOf("a" to 10L, "b" to 11L), LiveAlertRepository.announced(setOf("a", "b")))
        assertTrue(LiveAlertRepository.announced(emptySet()).isEmpty())
    }

    @Test fun `a released claim can be announced again and old records are pruned`() {
        LiveAlertRepository.claim("a", 10, now = 1_000)
        LiveAlertRepository.claim("a", 11, now = 5_000)
        LiveAlertRepository.release("a", 11)
        assertEquals(setOf("a" to 10L), LiveAlertRepository.announced(setOf("a")))
        LiveAlertRepository.claim("a", 12, now = 5_000)
        LiveAlertRepository.prune(postedBefore = 1_000)
        assertEquals(setOf("a" to 12L), LiveAlertRepository.announced(setOf("a")))
    }
}
