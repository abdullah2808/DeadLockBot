package bot

import api.gc.ProfileCard
import data.RankSnapshot
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.*

class RankTrackerTest {
    private var now = 100_000_000L
    private val snapshots = mutableMapOf<String, RankSnapshot>()
    private val badges = mutableMapOf<String, Int?>()
    private val fetched = mutableListOf<String>()
    private var failing = false
    private val tracker = RankTracker(
        fetch = { account ->
            fetched += account
            if (failing) throw IOException("GC busy")
            ProfileCard(account.toLong(), badges[account], emptyList())
        },
        all = { snapshots.toMap() },
        save = { account, snapshot -> snapshots[account] = snapshot; true },
        clock = { now },
        minIntervalMs = 0,
    )

    @Test
    fun `checks run at most once per interval`() = runTest {
        val paced = RankTracker({ fetched += it; null }, { emptyMap() }, { _, _ -> true }, { now })
        paced.checkDue(setOf("1"))
        now += 5_000
        paced.checkDue(setOf("2"))
        assertEquals(listOf("1"), fetched)
        now += 30_000
        paced.checkDue(setOf("2"))
        assertEquals(listOf("1", "2"), fetched)
    }

    @Test
    fun `first sighting records a baseline without announcing`() = runTest {
        badges["1"] = 54
        assertTrue(tracker.checkDue(setOf("1")).isEmpty())
        assertEquals(RankSnapshot(54, now), snapshots["1"])
    }

    @Test
    fun `a ranked match schedules checks that report a rank up once`() = runTest {
        snapshots["1"] = RankSnapshot(54, now)
        badges["1"] = 55
        tracker.rankedMatchFinished("1")
        assertTrue(tracker.checkDue(setOf("1")).isEmpty(), "not due until the post-match delay")
        assertTrue(fetched.isEmpty())
        now += 2 * 60_000L
        val change = tracker.checkDue(setOf("1")).single()
        assertEquals("1" to RankChange(54, 55), change)
        assertEquals(RankChange.Direction.UP, change.second.direction)
        // The change ended the post-match schedule, and the snapshot is fresh.
        now += 15 * 60_000L
        assertTrue(tracker.checkDue(setOf("1")).isEmpty())
        assertEquals(1, fetched.size)
    }

    @Test
    fun `an unchanged rank right after the match is checked again later`() = runTest {
        snapshots["1"] = RankSnapshot(54, now)
        badges["1"] = 54
        tracker.rankedMatchFinished("1")
        now += 2 * 60_000L
        assertTrue(tracker.checkDue(setOf("1")).isEmpty())
        badges["1"] = 53
        now += 13 * 60_000L
        assertEquals(RankChange.Direction.DOWN, tracker.checkDue(setOf("1")).single().second.direction)
        assertEquals(2, fetched.size)
    }

    @Test
    fun `hidden ranks keep the last badge and a first visible rank is a placement`() = runTest {
        snapshots["1"] = RankSnapshot(54, 0)
        badges["1"] = null
        assertTrue(tracker.checkDue(setOf("1")).isEmpty())
        assertEquals(54, snapshots["1"]?.badge)
        snapshots["2"] = RankSnapshot(null, 0)
        badges["2"] = 31
        assertEquals(RankChange.Direction.PLACED, tracker.checkDue(setOf("2")).single().second.direction)
    }

    @Test
    fun `a refused save announces nothing even if an old snapshot survived`() = runTest {
        val refusing = RankTracker({ ProfileCard(it.toLong(), 60, emptyList()) }, { mapOf("1" to RankSnapshot(54, 0)) }, { _, _ -> false }, { now }, 0)
        assertTrue(refusing.checkDue(setOf("1")).isEmpty())
    }

    @Test
    fun `stale accounts refresh a few per cycle and failures back off`() = runTest {
        val accounts = setOf("1", "2", "3")
        accounts.forEach { snapshots[it] = RankSnapshot(10, now); badges[it] = 10 }
        assertTrue(tracker.checkDue(accounts).isEmpty())
        assertTrue(fetched.isEmpty(), "fresh snapshots are not re-checked")
        now += RankTracker.REFRESH_MS
        tracker.checkDue(accounts)
        assertEquals(RankTracker.CHECKS_PER_CYCLE, fetched.size)
        fetched.clear()
        failing = true
        tracker.checkDue(accounts)
        assertEquals(listOf("3"), fetched)
        fetched.clear()
        tracker.checkDue(accounts)
        assertTrue(fetched.isEmpty(), "failed account waits for its back-off")
        failing = false
        now += RankTracker.FAILURE_BACKOFF_MS
        tracker.checkDue(accounts)
        assertEquals(listOf("3"), fetched)
    }
}
