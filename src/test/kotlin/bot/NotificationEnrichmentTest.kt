package bot

import data.PostedNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import models.MatchDTO
import models.MatchInfoDTO
import models.Players
import models.toMatchHistory
import kotlin.test.*

class NotificationEnrichmentTest {
    private val ready = PostedNotification("1", 99, "10", "20", 0)
    private val waiting = PostedNotification("1", 100, "10", "21", 0)
    private val metadata = MatchDTO(MatchInfoDTO(matchId = 99, startTime = 123, durationSeconds = 1800, winningTeam = 1,
        players = listOf(Players(1, heroId = 88, kills = 7))))

    @Test fun `posted summaries are edited once metadata arrives and then dequeued`() = runTest {
        val edited = mutableListOf<Long>()
        val removed = mutableListOf<Long>()
        enrichPostedNotifications(
            awaiting = { listOf(ready, waiting) },
            lookup = { if (it.matchId == 99L) metadata else null },
            summary = { n, m -> m.toMatchHistory(n.accountId) },
            edit = { n, summary, m -> edited += n.matchId; assertEquals(7, summary.kills); assertSame(metadata, m); true },
            remove = { removed += it.matchId },
        )
        assertEquals(listOf(99L), edited)
        assertEquals(listOf(99L), removed)
    }

    @Test fun `a failed edit stays queued and does not stop other notifications`() = runTest {
        val removed = mutableListOf<Long>()
        val other = ready.copy(accountId = "2", messageId = "22")
        enrichPostedNotifications(
            awaiting = { listOf(ready, other) },
            lookup = { metadata },
            summary = { n, m -> m.toMatchHistory("1") },
            edit = { n, _, _ -> if (n.accountId == "1") error("Discord unavailable"); true },
            remove = { removed += it.matchId },
        )
        assertEquals(listOf(99L), removed)
    }

    @Test fun `a declined edit is kept and an unavailable queue is tolerated`() = runTest {
        enrichPostedNotifications({ listOf(ready) }, { metadata }, { n, m -> m.toMatchHistory("1") }, { _, _, _ -> false },
            { fail("Declined edits must stay queued") })
        enrichPostedNotifications({ error("database down") }, { fail("No lookup expected") }, { _, _ -> null },
            { _, _, _ -> true }, { })
    }

    @Test fun `cancellation propagates`() = runTest {
        assertFailsWith<CancellationException> {
            enrichPostedNotifications({ listOf(ready) }, { throw CancellationException() }, { _, _ -> null }, { _, _, _ -> true }, { })
        }
    }
}
