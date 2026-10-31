package data

import api.MatchMetadataService
import api.MetadataLookup
import api.gc.CachedMatchHistoryProvider
import api.gc.GcMetadataDownload
import api.gc.MatchHistoryProvider
import api.gc.MatchHistorySnapshot
import bot.unsubscribeMessage
import kotlinx.coroutines.test.runTest
import models.MatchDTO
import models.MatchInfoDTO
import models.MatchHistoryDTO
import models.Players
import models.toMatchHistory
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.*

class TrackedAccountDataTest {
    private fun setup() {
        Database.connect("jdbc:h2:mem:tracked-${System.nanoTime()};DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(UserTable, CachedMatchTable, CachedPlayerMatchTable, WeeklySettingsTable, WeeklyMatchTable) }
    }
    private fun track(account: String) = UserRepository.addUser("discord-$account", account, "channel", "Player $account")
    private fun metadata(id: Long, vararg accounts: Int) = MatchDTO(MatchInfoDTO(matchId = id, startTime = 123, durationSeconds = 1800,
        winningTeam = 1, players = accounts.map { Players(accountId = it, heroId = 88, team = 1, kills = 7) }))
    private fun match(id: Long, account: Int): MatchHistoryDTO = metadata(id, account).toMatchHistory(account.toString())!!
    private fun archive(account: String, id: Long) = transaction {
        WeeklyMatchTable.insert { it[accountId] = account; it[matchId] = id; it[startTime] = 123; it[summary] = "{}" }
    }
    private fun archived(account: String) = transaction {
        WeeklyMatchTable.selectAll().where { WeeklyMatchTable.accountId eq account }.count()
    }
    private fun storedMatches() = transaction { CachedMatchTable.selectAll().map { it[CachedMatchTable.matchId] }.toSet() }

    @Test fun `unsubscribe deletes the account's rows and keeps matches teammates still reference`() {
        setup()
        track("107"); track("208")
        MatchCacheRepository.save("107", listOf(match(1, 107), match(2, 107)))
        MatchCacheRepository.save("208", listOf(match(1, 208), match(3, 208)))
        val shared = metadata(1, 107, 208)
        MatchCacheRepository.saveMetadata(shared)
        archive("107", 1); archive("208", 1)
        WeeklyDigestRepository.enable(UserRepository.selectUserByDiscordID("discord-107")!!)

        assertEquals(UnsubscribeOutcome.DATA_DELETED, TrackedAccountData.unsubscribe("discord-107"))

        assertFalse(UserRepository.isTracked("107"))
        assertNull(MatchCacheRepository.find("107", 1))
        assertEquals(0, archived("107"))
        transaction { assertTrue(WeeklySettingsTable.selectAll().empty()) }
        // Match 2 was only this player's; match 1 is still shared with 208 and keeps its metadata.
        assertEquals(setOf(1L, 3L), storedMatches())
        assertEquals(listOf("208"), MatchCacheRepository.participants(1))
        assertNotNull(MatchCacheRepository.find("208", 1))
        assertEquals(shared, MatchCacheRepository.findMetadata(1))
        assertEquals(1, archived("208"))

        assertEquals(UnsubscribeOutcome.DATA_DELETED, TrackedAccountData.unsubscribe("discord-208"))
        assertEquals(emptySet(), storedMatches())
    }

    @Test fun `failed cleanup still unsubscribes and the sweep finishes the deletion`() {
        setup()
        track("107"); track("208")
        MatchCacheRepository.save("107", listOf(match(1, 107)))
        MatchCacheRepository.save("208", listOf(match(1, 208)))
        transaction { SchemaUtils.drop(WeeklyMatchTable) }

        assertEquals(UnsubscribeOutcome.CLEANUP_PENDING, TrackedAccountData.unsubscribe("discord-107"))

        assertFalse(UserRepository.isTracked("107"))
        assertNull(UserRepository.selectUserByDiscordID("discord-107"))
        assertNotNull(MatchCacheRepository.find("107", 1)) // rolled back as one cleanup, so nothing is half deleted

        transaction { SchemaUtils.create(WeeklyMatchTable) }
        archive("107", 1)
        TrackedAccountData.sweepUntracked()

        assertNull(MatchCacheRepository.find("107", 1))
        assertEquals(0, archived("107"))
        assertNotNull(MatchCacheRepository.find("208", 1))
    }

    @Test fun `sweep removes legacy looked up accounts and leaves tracked accounts alone`() {
        setup()
        track("208")
        WeeklyDigestRepository.enable(UserRepository.selectUserByDiscordID("discord-208")!!)
        MatchCacheRepository.save("208", listOf(match(1, 208)))
        MatchCacheRepository.save("999", listOf(match(1, 999), match(2, 999)))
        archive("208", 1); archive("999", 2)
        transaction {
            WeeklySettingsTable.insert { it[discordId] = "gone"; it[accountId] = "999"; it[channelId] = "channel"; it[enabledAt] = 1 }
        }

        TrackedAccountData.sweepUntracked()

        assertEquals(listOf("208"), MatchCacheRepository.participants(1))
        assertEquals(setOf(1L), storedMatches())
        assertEquals(1, archived("208"))
        assertEquals(0, archived("999"))
        transaction { assertEquals(listOf("208"), WeeklySettingsTable.selectAll().map { it[WeeklySettingsTable.accountId] }) }
    }

    @Test fun `unsubscribing without a signup touches nothing`() {
        setup()
        track("208")
        MatchCacheRepository.save("208", listOf(match(1, 208)))
        assertEquals(UnsubscribeOutcome.NOT_SIGNED_UP, TrackedAccountData.unsubscribe("someone-else"))
        assertNotNull(MatchCacheRepository.find("208", 1))
    }

    @Test fun `reply only claims a deletion that happened`() {
        assertTrue("deleted your saved match history" in unsubscribeMessage(UnsubscribeOutcome.DATA_DELETED))
        assertTrue("couldn't delete" in unsubscribeMessage(UnsubscribeOutcome.CLEANUP_PENDING))
        assertFalse("deleted" in unsubscribeMessage(UnsubscribeOutcome.NOT_SIGNED_UP))
    }

    /** A Steam stub wired to the real repositories the way Main wires them. */
    private inner class Wiring(private val history: List<MatchHistoryDTO> = emptyList(), private val answer: (Long) -> GcMetadataDownload? = { GcMetadataDownload(it, 1, 2) }) {
        val targets = mutableListOf<String?>()
        var downloads = 0
        val gc = object : MatchHistoryProvider {
            override suspend fun recentMatchIds(accountId: String) = history.map { it.matchId }
            override suspend fun recentHistory(accountId: String) = MatchHistorySnapshot(history.map { it.matchId }, history)
            override suspend fun metadataDownload(targetAccountId: String?, matchId: Long, tracked: Boolean): GcMetadataDownload? {
                targets += targetAccountId
                return answer(matchId)
            }
        }
        val provider = CachedMatchHistoryProvider(gc)
        fun service(document: MatchDTO) = MatchMetadataService(gc, download = { downloads++; document }, fallback = { null },
            find = MatchCacheRepository::findMetadata,
            save = { MatchCacheRepository.saveEnrichedMetadata(it, UserRepository.getAllUsers().map { user -> user.accountId }.toSet()) },
            knownMatch = { account, id -> MatchCacheRepository.find(account, id) != null },
            participants = MatchCacheRepository::participants)
    }

    @Test fun `looked up account still gets metadata but stores nothing`() = runTest {
        setup()
        val wiring = Wiring(listOf(match(99, 107)))
        assertEquals(99L, wiring.provider.recentHistory("107").summaries.single().matchId)
        assertEquals(emptyList(), MatchCacheRepository.participants(99))
        assertEquals(emptySet(), storedMatches())

        val service = wiring.service(metadata(99, 107))
        assertEquals(metadata(99, 107), service.metadataFor("107", 99, interactive = true))
        assertEquals(listOf<String?>("107"), wiring.targets) // the named player is still the GC target
        assertEquals(emptySet(), storedMatches())
        assertNull(MatchCacheRepository.findMetadata(99))

        // Remembered in memory, so a repeat lookup spends no Steam quota.
        assertEquals(metadata(99, 107), service.metadataFor("107", 99, interactive = true))
        assertEquals(1, wiring.targets.size)
        assertEquals(1, wiring.downloads)
    }

    @Test fun `unavailable metadata for a looked up account does not promise background retries`() = runTest {
        setup()
        val wiring = Wiring(answer = { null })
        val outcome = wiring.service(metadata(99, 107)).lookup("107", 99, interactive = true)
        assertFalse(assertIs<MetadataLookup.Unavailable>(outcome).retrying)
    }

    @Test fun `tracked player's history still anchors metadata and background retries`() = runTest {
        setup()
        track("208")
        val wiring = Wiring(listOf(match(99, 208)))
        wiring.provider.recentHistory("208")
        assertEquals(listOf("208"), MatchCacheRepository.participants(99))

        // A looked up teammate is not indexed, but the document attaches to the tracked player's match row.
        assertEquals(metadata(99, 107, 208), wiring.service(metadata(99, 107, 208)).metadataFor("107", 99, interactive = true))
        assertEquals(listOf<String?>("208"), wiring.targets)
        assertEquals(metadata(99, 107, 208), MatchCacheRepository.findMetadata(99))
        assertEquals(listOf("208"), MatchCacheRepository.participants(99))
    }

    @Test fun `metadata enrichment after unsubscribing does not recreate the account's history`() = runTest {
        setup()
        track("208")
        MatchCacheRepository.save("208", listOf(match(99, 208)))
        TrackedAccountData.unsubscribe("discord-208")

        val wiring = Wiring()
        assertEquals(metadata(99, 208), wiring.service(metadata(99, 208)).metadataFor("208", 99))
        assertEquals(emptySet(), storedMatches())
    }
}
