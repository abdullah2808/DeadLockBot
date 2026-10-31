package bot

import data.LiveAlertChannelTable
import data.LiveAlertPostTable
import data.LiveAlertRepository
import data.UserRepository.UserRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import models.ActiveMatchDTO
import models.ActiveMatchPlayer
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.IOException
import kotlin.test.*

class MatchAlertsTest {
    private fun user(account: Long, channel: String?) = UserRecord("d$account", "$account", "P$account", null, channel)
    private val users = listOf(user(1, "a"), user(2, "a"), user(3, "b"), user(4, null))
    private val shared = ActiveMatchDTO(matchId = 10, players = listOf(ActiveMatchPlayer(1), ActiveMatchPlayer(2, 1), ActiveMatchPlayer(3), ActiveMatchPlayer(99)))

    @Test fun `channels get nothing until they opt in`() {
        assertTrue(pendingMatchAlerts(users, emptySet(), listOf(shared), emptySet()).isEmpty())
        assertEquals(listOf("b"), pendingMatchAlerts(users, setOf("b"), listOf(shared), emptySet()).map { it.channelId })
    }

    @Test fun `tracked players in the same match share one alert per channel`() {
        val alerts = pendingMatchAlerts(users, setOf("a", "b"), listOf(shared), emptySet())
        assertEquals(listOf("a", "b"), alerts.map { it.channelId })
        assertEquals(listOf("P1", "P2"), alerts[0].listed.players.map { it.player.name }, "only that channel's players")
        assertEquals(listOf("P3"), alerts[1].listed.players.map { it.player.name })
    }

    @Test fun `an announced match is not repeated, even for a player who appears later, but other channels still get it`() {
        val alerts = pendingMatchAlerts(users, setOf("a", "b"), listOf(shared), setOf("a" to 10L))
        assertEquals(listOf("b"), alerts.map { it.channelId })
    }

    @Test fun `players who already left don't trigger or appear in an alert`() {
        val allLeft = ActiveMatchDTO(matchId = 11, players = listOf(ActiveMatchPlayer(1, abandoned = true)))
        assertTrue(pendingMatchAlerts(users, setOf("a"), listOf(allLeft), emptySet()).isEmpty())
        val oneLeft = ActiveMatchDTO(matchId = 12, players = listOf(ActiveMatchPlayer(1, abandoned = true), ActiveMatchPlayer(2)))
        assertEquals(listOf("P2"), pendingMatchAlerts(users, setOf("a"), listOf(oneLeft), emptySet()).single().listed.players.map { it.player.name })
    }

    private val alert = MatchAlert("a", ListedMatch(shared, emptyList()))

    @Test fun `delivery claims before sending`() = runTest {
        val order = mutableListOf<String>()
        deliverMatchAlerts(listOf(alert), { order += "claim" }, { order += "send" }, { order += "release" })
        assertEquals(listOf("claim", "send"), order)
    }

    @Test fun `a transient send failure gives the claim back and later alerts still post`() = runTest {
        val sent = mutableListOf<Long>()
        val released = mutableListOf<Long>()
        val other = MatchAlert("a", ListedMatch(shared.copy(matchId = 11), emptyList()))
        deliverMatchAlerts(listOf(alert, other), {}, {
            if (it === alert) throw IOException("Discord is down")
            sent += it.listed.match.matchId
        }, { released += it.listed.match.matchId })
        assertEquals(listOf(10L), released)
        assertEquals(listOf(11L), sent)
    }

    @Test fun `a permanent send failure keeps the claim so it isn't retried every poll`() = runTest {
        var released = false
        deliverMatchAlerts(listOf(alert), {}, { throw IOException("gone") }, { released = true }, permanent = { true })
        assertFalse(released)
    }

    @Test fun `no alert posts when the claim can't be recorded, because it could repeat`() = runTest {
        var sent = false
        deliverMatchAlerts(listOf(alert), { throw IOException("db down") }, { sent = true }, {})
        assertFalse(sent)
    }

    @Test fun `shutting down mid-send keeps the claim, because Discord may already have the post`() = runTest {
        var released = false
        assertFailsWith<CancellationException> {
            deliverMatchAlerts(listOf(alert), {}, { throw CancellationException("stopping") }, { released = true })
        }
        assertFalse(released)
    }

    @Test fun `the live list is not fetched unless an opted-in channel has a tracked player`() = runTest {
        var fetches = 0
        val fetch: suspend () -> List<ActiveMatchDTO> = { fetches++; listOf(shared) }
        checkMatchAlerts(users, { emptySet() }, fetch, { emptySet() }, {})
        checkMatchAlerts(users, { setOf("nobody-here") }, fetch, { emptySet() }, {})
        assertEquals(0, fetches)
        checkMatchAlerts(users, { setOf("a") }, fetch, { emptySet() }, {})
        assertEquals(1, fetches)
    }

    @Test fun `a match is announced once across polls and a restart, using the stored record`() = runTest {
        Database.connect("jdbc:h2:mem:alerts-${System.nanoTime()};DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(LiveAlertChannelTable, LiveAlertPostTable) }
        LiveAlertRepository.enable("a", "d1")
        val posts = mutableListOf<MatchAlert>()
        // Everything in memory is rebuilt for each poll, so the second poll also stands for a restarted process.
        suspend fun poll() = checkMatchAlerts(users, LiveAlertRepository::enabledChannels, { listOf(shared) }, LiveAlertRepository::announced) { alerts ->
            deliverMatchAlerts(alerts, { LiveAlertRepository.claim(it.channelId, it.listed.match.matchId) }, { posts += it },
                { LiveAlertRepository.release(it.channelId, it.listed.match.matchId) })
        }
        poll()
        poll()
        assertEquals(1, posts.size)
    }

    @Test fun `only tracked players here or channel managers can change alerts`() {
        assertTrue(canChangeMatchAlerts(registeredHere = true, managesChannels = false))
        assertTrue(canChangeMatchAlerts(registeredHere = false, managesChannels = true))
        assertFalse(canChangeMatchAlerts(registeredHere = false, managesChannels = false))
    }

    @Test fun `replies state the coverage limit and the resulting state`() {
        val enabled = matchAlertsReply("enable", enabled = true, allowed = true, trackedHere = 2)
        assertContains(enabled, "are on")
        assertContains(enabled, "matches outside it never alert")
        assertFalse(enabled.contains("Nobody is tracked"))
        assertContains(matchAlertsReply("enable", enabled = true, allowed = true, trackedHere = 0), "Nobody is tracked")
        assertContains(matchAlertsReply("disable", enabled = false, allowed = true, trackedHere = 2), "are off")
        val denied = matchAlertsReply("enable", enabled = false, allowed = false, trackedHere = 2)
        assertContains(denied, "can manage channels")
        assertContains(denied, "currently off")
        assertContains(matchAlertsReply("status", enabled = true, allowed = false, trackedHere = 3), "Tracked players here: 3")
        assertContains(matchAlertsReply("status", enabled = true, allowed = false, trackedHere = 3), "matches outside it never alert")
        assertContains(matchAlertsReply(null, enabled = false, allowed = true, trackedHere = 0), "Choose")
    }
}
