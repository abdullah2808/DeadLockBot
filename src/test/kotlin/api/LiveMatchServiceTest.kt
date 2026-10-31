package api

import api.gc.MatchHistoryProvider
import api.gc.ProfileCard
import kotlinx.coroutines.test.runTest
import models.ActiveMatchDTO
import models.ActiveMatchPlayer
import java.io.IOException
import kotlin.test.*

class LiveMatchServiceTest {
    private val match = ActiveMatchDTO(matchId = 9, players = listOf(ActiveMatchPlayer(accountId = 107)))

    private fun gc(active: suspend () -> List<ActiveMatchDTO>?, profile: suspend (String) -> ProfileCard? = { null }) =
        object : MatchHistoryProvider {
            override suspend fun recentMatchIds(accountId: String) = emptyList<Long>()
            override suspend fun activeMatches() = active()
            override suspend fun profileCard(accountId: String) = profile(accountId)
        }

    @Test
    fun `GC live list is preferred and cached for a minute`() = runTest {
        var gcCalls = 0
        var now = 0L
        val service = LiveMatchService(gc({ gcCalls++; listOf(match) }), { error("API must not run") }, clock = { now })
        assertEquals(9, service.findLive(107)?.matchId)
        assertNull(service.findLive(108))
        assertTrue(service.activeMatches().fromGc)
        assertEquals(1, gcCalls)
        now = 60_000
        service.activeMatches()
        assertEquals(2, gcCalls)
    }

    @Test
    fun `deadlock-api supplies the list when the GC fails or is not configured`() = runTest {
        val failing = LiveMatchService(gc({ throw IOException("no answer") }), { listOf(match) })
        assertFalse(failing.activeMatches().fromGc)
        val noSteam = LiveMatchService(null, { listOf(match) })
        assertEquals(9, noSteam.findLive(107)?.matchId)
    }

    @Test
    fun `an error is raised only when every source failed`() = runTest {
        val service = LiveMatchService(gc({ throw IOException("gc") }), { throw IOException("api") })
        assertFailsWith<IOException> { service.activeMatches() }
        assertFailsWith<IllegalStateException> { LiveMatchService(null, null).activeMatches() }
        // An empty GC list is an answer, not a failure.
        assertEquals(emptyList(), LiveMatchService(gc({ emptyList() }), { error("API must not run") }).activeMatches().matches)
    }

    @Test
    fun `background checks leave a failing GC alone for two hours while commands can still ask it`() = runTest {
        var gcCalls = 0
        var now = 0L
        val service = LiveMatchService(gc({ gcCalls++; throw IOException("no answer") }), { listOf(match) }, clock = { now })
        assertFalse(service.activeMatches(background = true).fromGc)
        assertEquals(1, gcCalls)

        now = 60_000
        assertFalse(service.activeMatches(background = true).fromGc)
        assertEquals(1, gcCalls, "a background check must not ask the GC again yet")

        now = 120_000
        service.activeMatches()
        assertEquals(2, gcCalls, "commands aren't held back")

        now = 120_000 + 2 * 60 * 60_000L
        service.activeMatches(background = true)
        assertEquals(3, gcCalls, "the GC is tried again after the wait")
    }

    @Test
    fun `background checks share the one-minute snapshot with commands`() = runTest {
        var gcCalls = 0
        val service = LiveMatchService(gc({ gcCalls++; listOf(match) }), null, clock = { 0L })
        service.activeMatches(background = true)
        service.activeMatches()
        service.findLive(107)
        assertEquals(1, gcCalls)
    }

    @Test
    fun `profile cards are cached per account for five minutes`() = runTest {
        var calls = 0
        var now = 0L
        val service = LiveMatchService(gc({ null }, { ProfileCard(it.toLong(), 50, emptyList()).also { calls++ } }), null, clock = { now })
        assertEquals(50, service.profileCard("107")?.rankedBadgeLevel)
        service.profileCard("107")
        service.profileCard("108")
        assertEquals(2, calls)
        now = 5 * 60_000L
        service.profileCard("107")
        assertEquals(3, calls)
        assertNull(LiveMatchService(null, null).profileCard("107"))
    }
}
