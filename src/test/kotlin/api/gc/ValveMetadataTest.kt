package api.gc

import com.github.luben.zstd.Zstd
import gc.citadel.CitadelMetadata.CMsgMatchMetaData
import gc.citadel.CitadelMetadata.CMsgMatchMetaDataContents as Contents
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import models.toMatchHistory
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.*

class ValveMetadataTest {
    private fun fixture(id: Long = 99, scored: Boolean = true, outcome: Int = 0): ByteArray {
        val last = Contents.PlayerStats.newBuilder().setTimeStampS(1000).setKills(7)
            .setPlayerDamage(42000).setBossDamage(3000).setPlayerHealing(400).setDamageAbsorbed(600)
        val player = Contents.Players.newBuilder().setAccountId(-1).setHeroId(88).setTeam(1)
            .setDeaths(4).setAssists(12).setNetWorth(35000).setLevel(25)
            .setPlayerSlot(7)
            .addDeathDetails(Contents.Deaths.newBuilder().setGameTimeS(800).setKillerPlayerSlot(0).setDeathDurationS(20))
            .addItems(Contents.Items.newBuilder().setItemId(-1).setGameTimeS(300).setSoldTimeS(0).setUpgradeId(-1).setUpgradeInfo(983041))
            .addItems(Contents.Items.newBuilder().setItemId(123).setGameTimeS(100).setSoldTimeS(200))
            .addStats(last).addStats(Contents.PlayerStats.newBuilder().setTimeStampS(10).setPlayerDamage(5))
        val info = Contents.MatchInfo.newBuilder().setMatchId(id).setStartTime(1700000000)
            .setDurationS(1800).setWinningTeam(1).setMatchOutcome(outcome).setNotScored(!scored)
            .setGameMode(2).setMatchMode(3).setObjectivesMaskTeam0(31).setAverageBadgeTeam1(91)
            .addPlayers(player)
        return CMsgMatchMetaData.newBuilder().setVersion(1).setMatchId(id)
            .setMatchDetails(Contents.newBuilder().setMatchInfo(info).build().toByteString()).build().toByteArray()
    }
    @Test fun `raw zstd and bzip2 preserve final player stats and unsigned account`() {
        val raw = fixture()
        val bzip = ByteArrayOutputStream().also { output ->
            BZip2CompressorOutputStream(output).use { it.write(raw) }
        }.toByteArray()
        for (bytes in listOf(raw, Zstd.compress(raw), bzip)) {
            val decoded = decodeValveMetadata(bytes, 99)
            val player = decoded.matchInfo.players.single()
            assertEquals(42000, player.stats.single().playerDamage)
            assertEquals(3000, player.stats.single().objectiveDamage)
            assertEquals(7, player.kills)
            assertEquals(7, player.playerSlot)
            assertEquals(800, player.deathDetails!!.single().gameTimeSeconds)
            assertEquals(0, player.deathDetails.single().killerPlayerSlot)
            assertEquals(20, player.deathDetails.single().deathDurationSeconds)
            assertEquals(4294967295L, player.finalItems.single().itemId)
            assertEquals(4294967295L, player.finalItems.single().upgradeId)
            assertEquals(983041L, player.finalItems.single().upgradeInfo)
            assertEquals(1000, player.finalStats?.timeStampSeconds)
            assertEquals(91, decoded.matchInfo.averageBadgeTeam1)
            val summary = assertNotNull(decoded.toMatchHistory("4294967295"))
            assertEquals(25, summary.heroLevel)
            assertEquals(2, summary.gameMode)
            assertEquals(31L, summary.objectivesMaskTeam0)
        }
    }
    @Test fun `wrong match IDs invalid games incomplete contents and corrupt compression are rejected`() {
        assertFailsWith<IllegalArgumentException> { decodeValveMetadata(fixture(100), 99) }
        val wrongInner = CMsgMatchMetaData.parseFrom(fixture()).toBuilder().setMatchId(100).build().toByteArray()
        assertFailsWith<IllegalArgumentException> { decodeValveMetadata(wrongInner, 100) }
        assertFailsWith<IllegalArgumentException> { decodeValveMetadata(fixture(outcome = 1), 99) }
        assertFailsWith<IllegalArgumentException> { decodeValveMetadata(CMsgMatchMetaData.newBuilder().setMatchId(99).build().toByteArray(), 99) }
        assertFails { decodeValveMetadata(Zstd.compress(fixture()).copyOf(8), 99) }
    }
    @Test fun `draw and unscored games have neutral result`() {
        assertEquals(-1, decodeValveMetadata(fixture(scored = false), 99).matchInfo.winningTeam)
        assertEquals(-1, decodeValveMetadata(fixture(outcome = 2), 99).matchInfo.winningTeam)
        assertEquals(2, decodeValveMetadata(fixture(outcome = 2), 99).matchInfo.matchOutcome)
    }
    @Test fun `decoded size limit also applies to compressed data`() {
        assertFailsWith<IOException> { decodeValveMetadata(Zstd.compress(fixture()), 99, 10) }
    }
    @Test fun `Valve URL is fixed and contains no Steam credentials`() = runTest {
        val http = HttpClient(MockEngine { request ->
            assertEquals("http://replay121.valve.net/1422450/99_456.meta.bz2", request.url.toString())
            assertNull(request.headers["Authorization"])
            assertNull(request.headers["Cookie"])
            respond(Zstd.compress(fixture()))
        }) { followRedirects = false }
        ValveMetadataClient(http).use { assertEquals(99L, it.download(GcMetadataDownload(99, 121, 456)).matchInfo.matchId) }
        assertFailsWith<IllegalArgumentException> { GcMetadataDownload(99, -1, 456).url() }
    }
    @Test fun `download limit and unsuccessful HTTP responses reject without parsing`() = runTest {
        ValveMetadataClient(HttpClient(MockEngine { respond(ByteArray(101)) }), maxDownloadBytes = 100).use {
            assertFailsWith<IOException> { it.download(GcMetadataDownload(99, 121, 456)) }
        }
        ValveMetadataClient(HttpClient(MockEngine { respond("", HttpStatusCode.Found) }) { followRedirects = false }).use {
            assertFailsWith<IOException> { it.download(GcMetadataDownload(99, 121, 456)) }
        }
    }
}
