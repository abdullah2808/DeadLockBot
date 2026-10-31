package api.gc

import gc.citadel.CitadelLive
import java.io.IOException
import kotlin.test.*

class GcLiveTest {
    private fun slot(id: Int, build: CitadelLive.CMsgCitadelProfileCard.Slot.Builder.() -> Unit) =
        CitadelLive.CMsgCitadelProfileCard.Slot.newBuilder().setSlotId(id).apply(build).build()

    @Test
    fun `profile card orders heroes by slot and maps headline stats`() {
        val card = CitadelLive.CMsgCitadelProfileCard.newBuilder()
            .setAccountId(107)
            .setRankedBadgeLevel(74)
            .addSlots(slot(3) { hero = CitadelLive.CMsgCitadelProfileCard.Slot.Hero.newBuilder().setHeroId(2).setHeroWins(5).setHeroKills(40).build() })
            .addSlots(slot(1) { hero = CitadelLive.CMsgCitadelProfileCard.Slot.Hero.newBuilder().setHeroId(7).setHeroWins(9).setHeroKills(90).build() })
            .addSlots(slot(4) { stat = CitadelLive.CMsgCitadelProfileCard.Slot.Stat.newBuilder().setStatId(3).setStatScore(200).build() })
            .addSlots(slot(5) { stat = CitadelLive.CMsgCitadelProfileCard.Slot.Stat.newBuilder().setStatId(1).setStatScore(110).build() })
            .addSlots(slot(6) {})
            .build().toProfileCard("107")
        assertEquals(74, card.rankedBadgeLevel)
        assertEquals(listOf(ProfileHero(7, 9, 90), ProfileHero(2, 5, 40)), card.heroes)
        assertEquals(200, card.gamesPlayed)
        assertEquals(110, card.wins)
        assertNull(card.kills)
    }

    @Test
    fun `profile card treats a zero badge as unranked and rejects another account's card`() {
        val unranked = CitadelLive.CMsgCitadelProfileCard.newBuilder().setAccountId(107).setRankedBadgeLevel(0).build()
        assertNull(unranked.toProfileCard("107").rankedBadgeLevel)
        // Unsigned account IDs above Int.MAX_VALUE still compare correctly.
        val high = CitadelLive.CMsgCitadelProfileCard.newBuilder().setAccountId(-1).build()
        assertEquals(4294967295L, high.toProfileCard("4294967295").accountId)
        assertFailsWith<IOException> { unranked.toProfileCard("108") }
    }

    private fun activeResponse() = CitadelLive.CMsgClientToGCGetActiveMatchesResponse.newBuilder()
        .addActiveMatches(CitadelLive.CMsgDevMatchInfo.newBuilder()
            .setMatchId(55).setStartTime(1_000).setDurationS(600).setSpectators(12)
            .setNetWorthTeam0(30_000).setNetWorthTeam1(25_000).setMatchMode(4).setGameMode(1)
            .addPlayers(CitadelLive.CMsgDevMatchInfo.MatchPlayer.newBuilder().setAccountId(-2).setTeam(1).setHeroId(13).setAbandoned(true)))
        .build()

    @Test
    fun `active matches map unsigned account IDs and match details`() {
        val match = activeResponse().activeMatchesList.single().toActiveMatch()
        assertEquals(55, match.matchId)
        assertEquals(4294967294L, match.players.single().accountId)
        assertEquals(1, match.players.single().team)
        assertEquals(true, match.players.single().abandoned)
        assertEquals(30_000, match.netWorthTeam0)
        assertEquals(4, match.matchMode)
    }

    /** Literal-only Snappy encoding, enough to exercise the decoder's framing. */
    private fun snappyLiterals(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var length = data.size
        while (length >= 0x80) { out.write((length and 0x7F) or 0x80); length = length ushr 7 }
        out.write(length)
        data.toList().chunked(256).forEach { chunk ->
            out.write(60 shl 2)
            out.write(chunk.size - 1)
            out.write(chunk.toByteArray())
        }
        return out.toByteArray()
    }

    @Test
    fun `active matches decode plain, Snappy and prefixed Snappy bodies`() {
        val plain = activeResponse().toByteArray()
        assertEquals(55, decodeActiveMatches(plain).activeMatchesList.single().matchId)
        assertEquals(55, decodeActiveMatches(snappyLiterals(plain)).activeMatchesList.single().matchId)
        val prefixed = ByteArray(7) { 0x7F } + snappyLiterals(plain)
        assertEquals(55, decodeActiveMatches(prefixed).activeMatchesList.single().matchId)
        assertEquals(0, decodeActiveMatches(ByteArray(0)).activeMatchesCount)
    }

    @Test
    fun `Snappy decoder handles overlapping copies and rejects malformed input`() {
        // "abcd" literal, then copy 8 bytes from offset 4.
        val encoded = byteArrayOf(12, (3 shl 2).toByte(), 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 'd'.code.toByte(), 17, 4)
        assertEquals("abcdabcdabcd", String(snappyDecompress(encoded)))
        assertFailsWith<IOException> { snappyDecompress(byteArrayOf(12, 17, 4)) } // copy before any output
        assertFailsWith<IOException> { snappyDecompress(byteArrayOf(5, 0, 'a'.code.toByte())) } // ends early
        assertFailsWith<IOException> { snappyDecompress(byteArrayOf(-1, -1, -1, -1, 0x0F), maxLength = 1024) }
    }

    @Test
    fun `GC packet body skips the protobuf header`() {
        val packet = byteArrayOf(0x2C, 0x24, 0, -128, 2, 0, 0, 0, 9, 9, 1, 2, 3)
        assertContentEquals(byteArrayOf(1, 2, 3), gcProtobufBody(packet))
        assertFailsWith<IOException> { gcProtobufBody(byteArrayOf(0, 0, 0, 0, 50, 0, 0, 0)) }
    }
}
