package data

import models.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.*

class WeeklyDigestRepositoryTest {
    @Test fun `opt in archive boundaries dedupe delivery and current registration are persisted`() {
        Database.connect("jdbc:h2:mem:weekly-${System.nanoTime()};DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(WeeklySettingsTable, WeeklyMatchTable, WeeklyDeliveryTable) }
        val user = UserRepository.UserRecord("discord", "1", "Player", null, "channel")
        val start = 1700000000L
        val match = MatchDTO(MatchInfoDTO(matchId = 1, startTime = start.toInt(), durationSeconds = 600,
            winningTeam = 0, players = listOf(Players(1, team = 0)))).toMatchHistory("1")!!
        WeeklyDigestRepository.record("1", listOf(match), start + 1)
        assertTrue(WeeklyDigestRepository.matches("1", start, start + 1).isEmpty())
        WeeklyDigestRepository.enable(user)
        WeeklyDigestRepository.record("1", listOf(match, match), start + 1)
        assertEquals(1, WeeklyDigestRepository.matches("1", start, start + 1).size)
        assertTrue(WeeklyDigestRepository.matches("1", start - 1, start).isEmpty())
        assertEquals(listOf(user), WeeklyDigestRepository.subscribers(listOf(user)))
        assertTrue(WeeklyDigestRepository.subscribers(listOf(user), eligibleBefore = 1L).isEmpty())
        assertTrue(WeeklyDigestRepository.subscribers(listOf(user.copy(accountId = "2"))).isEmpty())
        WeeklyDigestRepository.markDelivered("channel", start)
        assertTrue(WeeklyDigestRepository.delivered("channel", start))
        WeeklyDigestRepository.disable(user.discordId)
        assertTrue(WeeklyDigestRepository.subscribers(listOf(user)).isEmpty())
    }
}
