package bot

import kotlinx.coroutines.test.runTest
import java.time.Instant
import models.toMatchHistory
import kotlin.test.*

class WeeklyDigestTest {
    @Test fun `Monday boundary and Sunday use exact half-open completed weeks`() {
        val monday = Instant.parse("2026-10-26T00:00:00Z")
        assertEquals(Instant.parse("2026-10-19T00:00:00Z").epochSecond to monday.epochSecond, completedWeek(monday))
        assertEquals(completedWeek(monday), completedWeek(Instant.parse("2026-11-01T23:59:59Z")))
    }
    @Test fun `delivery is remembered after acceptance and skipped on restart`() = runTest {
        val calls = mutableListOf<String>(); var sent = false
        suspend fun attempt() = deliverWeekly({ sent }, { calls += "send" }, { calls += "persist"; sent = true })
        attempt(); attempt()
        assertEquals(listOf("send", "persist"), calls)
    }
    @Test fun `weekly summary compares weighted samples and shows weekly personal best`() {
        fun match(id: Long, duration: Int, result: Int) = models.MatchDTO(models.MatchInfoDTO(matchId = id,
            durationSeconds = duration, winningTeam = result, players = listOf(models.Players(1, team = 0,
                heroId = 13, netWorth = 6000, kills = 16))))
        val recent = match(1, 1200, 0).toMatchHistory("1")!!
        val old = match(2, 600, 1).toMatchHistory("1")!!
        val text = weeklyPlayerLine("Player", listOf(recent), { "Haze" }, listOf(old))
        assertContains(text, "Weekly personal best: 16 kills")
        assertContains(text, "+100.0 win-rate points")
        assertContains(text, "-300 souls/min")
    }
    @Test fun `rejected delivery remains retryable`() = runTest {
        var persisted = false
        assertFailsWith<IllegalStateException> { deliverWeekly({ false }, { error("Discord rejected") }, { persisted = true }) }
        assertFalse(persisted)
    }
}
