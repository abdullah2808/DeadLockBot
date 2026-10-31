package bot

import models.*
import kotlin.test.*

class TrendsTest {
    private fun sample(id: Long, duration: Int = 600, result: Int = 0, damage: Int? = null): TrendSample {
        val info = MatchDTO(MatchInfoDTO(matchId = id, startTime = id.toInt(), durationSeconds = duration, winningTeam = result,
            players = listOf(Players(1, team = 0, netWorth = 6000, deaths = 2,
                stats = listOf(Stats(playerDamage = damage))))))
        return TrendSample(info.toMatchHistory("1")!!, info)
    }
    @Test fun `uses disjoint windows and weights rates by minutes`() {
        val report = trendReport((1L..40L).map { sample(it, if (it > 20) 1200 else 600, if (it > 20) 0 else 1, 12000) })
        assertContains(report, "Latest 20 vs previous 20")
        assertContains(report, "Win rate (%): 100.0 · previous 0.0")
        assertContains(report, "Souls / min: 300.0 · previous 600.0")
        assertContains(report, "Damage / min (20/20 matches): 600.0")
    }
    @Test fun `duplicates unknown outcomes and zero durations do not pollute sample`() {
        val valid = sample(3)
        val report = trendReport(listOf(valid, valid, sample(4, 0), sample(5, result = -1)))
        assertContains(report, "Latest 1 vs previous 0")
        assertContains(report, "Damage / min (0/1 matches): Unavailable")
        assertContains(report, "1/40")
        assertContains(trendReport(emptyList()), "No completed")
    }
}
