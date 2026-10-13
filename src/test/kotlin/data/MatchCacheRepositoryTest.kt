package data

import models.MatchDTO
import models.MatchInfoDTO
import models.Players
import models.toMatchHistory
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.*

class MatchCacheRepositoryTest {
    @Test fun `API enrichment preserves existing GC summary fields omitted by API`() {
        setup()
        val summary = match(99, 107).copy(heroLevel = 25, gameMode = 2, objectivesMaskTeam0 = 31)
        MatchCacheRepository.save("107", listOf(summary))
        val metadata = MatchDTO(MatchInfoDTO(matchId = 99, startTime = 123, durationSeconds = 1800,
            winningTeam = 1, players = listOf(Players(107, heroId = 88, kills = 7))))
        MatchCacheRepository.saveEnrichedMetadata(metadata, setOf("107"))
        assertEquals(summary, MatchCacheRepository.find("107", 99))
        assertEquals(metadata, MatchCacheRepository.findMetadata(99))
    }
    @Test fun `enrichment indexes registered party members once and keeps unregistered players only in full metadata`() {
        setup()
        val metadata = MatchDTO(MatchInfoDTO(matchId = 99, startTime = 123, durationSeconds = 1800, winningTeam = 1,
            players = listOf(Players(107, heroId = 88, kills = 7), Players(208, heroId = 85, kills = 2),
                Players(-1, heroId = 14, kills = 9), Players(999, heroId = 10, kills = 3))))
        MatchCacheRepository.saveEnrichedMetadata(metadata, setOf("107", "208", "4294967295"))
        MatchCacheRepository.saveEnrichedMetadata(metadata, setOf("107", "208", "4294967295"))
        assertEquals(7, MatchCacheRepository.find("107", 99)!!.kills)
        assertEquals(2, MatchCacheRepository.find("208", 99)!!.kills)
        assertEquals(9, MatchCacheRepository.find("4294967295", 99)!!.kills)
        assertNull(MatchCacheRepository.find("999", 99))
        assertEquals(metadata, MatchCacheRepository.findMetadata(99))
        transaction {
            assertEquals(1L, CachedMatchTable.selectAll().count())
            assertEquals(3L, CachedPlayerMatchTable.selectAll().count())
        }
    }
    private fun setup() {
        Database.connect("jdbc:h2:mem:cache-${System.nanoTime()};DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(CachedMatchTable, CachedPlayerMatchTable) }
    }
    private fun match(id: Long, account: Int, hero: Int = 88, kills: Int = 7) = MatchDTO(MatchInfoDTO(
        matchId = id, startTime = 123, durationSeconds = 1800, winningTeam = 1,
        players = listOf(Players(accountId = account, heroId = hero, team = 1, kills = kills)),
    )).toMatchHistory(account.toString())!!

    @Test
    fun `party shares one match with separate player stats and metadata`() {
        setup()
        MatchCacheRepository.save("107", listOf(match(99, 107)))
        val metadata = MatchDTO(MatchInfoDTO(matchId = 99, durationSeconds = 1800, winningTeam = 1, players = emptyList()))
        MatchCacheRepository.saveMetadata(metadata)
        MatchCacheRepository.save("208", listOf(match(99, 208, hero = 85, kills = 2)))

        transaction {
            assertEquals(1L, CachedMatchTable.selectAll().count())
            assertEquals(2L, CachedPlayerMatchTable.selectAll().count())
        }
        assertEquals(7, MatchCacheRepository.find("107", 99)!!.kills)
        assertEquals(2, MatchCacheRepository.find("208", 99)!!.kills)
        assertEquals(85, MatchCacheRepository.find("208", 99)!!.heroId)
        assertEquals(metadata, MatchCacheRepository.findMetadata(99))
    }

    @Test
    fun `trimming one account preserves a party member's shared match`() {
        setup()
        MatchCacheRepository.save("107", listOf(match(1, 107)))
        MatchCacheRepository.save("208", listOf(match(1, 208)))
        MatchCacheRepository.save("107", (2L..27L).map { match(it, 107) })

        assertNull(MatchCacheRepository.find("107", 1))
        assertNotNull(MatchCacheRepository.find("208", 1))
        transaction {
            assertEquals(25L, CachedPlayerMatchTable.selectAll().where { CachedPlayerMatchTable.accountId eq "107" }.count())
            assertEquals(26L, CachedMatchTable.selectAll().count())
        }
    }

    @Test
    fun `refreshing the same cached row preserves metadata and does not duplicate it`() {
        setup()
        val summary = match(99, 107)
        MatchCacheRepository.save("107", listOf(summary))
        val metadata = MatchDTO(MatchInfoDTO(matchId = 99, players = emptyList()))
        MatchCacheRepository.saveMetadata(metadata)
        MatchCacheRepository.save("107", listOf(summary))

        assertEquals(summary, MatchCacheRepository.find("107", 99))
        assertEquals(metadata, MatchCacheRepository.findMetadata(99))
        transaction { assertEquals(1L, CachedPlayerMatchTable.selectAll().count()) }
    }
}
