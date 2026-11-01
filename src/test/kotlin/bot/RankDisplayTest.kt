package bot

import api.DeadlockClient
import api.gc.ProfileCard
import api.gc.decodeValveMetadata
import api.gc.toSummary
import data.CachedMatchTable
import data.CachedPlayerMatchTable
import data.DatabaseFactory.addRankColumns
import data.MatchCacheRepository
import gc.citadel.CitadelMatchHistory.CMsgClientToGCGetMatchHistoryResponse.Match
import gc.citadel.CitadelMetadata.CMsgMatchMetaData
import gc.citadel.CitadelMetadata.CMsgMatchMetaDataContents as Contents
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import models.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import util.LiveMessageGenerator
import util.MatchMessageGenerator
import kotlin.test.*

class RankDisplayTest {
    private val names: (Int) -> String = { "R$it" }
    private fun summary(matchMode: Int = 4, gameMode: Int = 1, badge: Int? = null, delta: Int? = null, calibration: Int? = null, protection: Boolean? = null) =
        MatchHistoryDTO(accountId = 42, denies = 1, gameMode = gameMode, heroId = 13, heroLevel = 24, lastHits = 190,
            matchDurationSeconds = 1805, matchId = 7, matchMode = matchMode, matchResult = 0, netWorth = 54321,
            objectivesMaskTeam0 = 0, objectivesMaskTeam1 = 0, assists = 20, deaths = 4, kills = 12, playerTeam = 0,
            startTime = 1700000000, rankedDisplayBadge = badge, rankedDelta = delta,
            rankedCalibrationMatch = calibration, rankedUsedDemotionProtection = protection)
    private fun player(account: Int, team: Int, badge: Int?, start: Int? = null, end: Int? = null) =
        Players(account, team = team, rankData = PlayerRankData(badge, start, end))
    private fun details(vararg players: Players) = MatchDTO(MatchInfoDTO(matchId = 7, players = players.toList()))

    @Test fun `averages walk the rank ladder because badges are not linear`() {
        val ranks = matchRanks(MatchInfoDTO(players = listOf(player(1, 0, 116), player(2, 0, 121), player(3, 1, 114), player(4, 1, 114))))
        assertEquals(121, ranks.team0) // 116 is directly followed by 121, so their midpoint rounds up to it.
        assertEquals(114, ranks.team1)
        assertEquals(115, ranks.overall)
    }

    @Test fun `players without a usable rank are left out and reported averages are only a fallback`() {
        val ranks = matchRanks(MatchInfoDTO(averageBadgeTeam0 = 50, averageBadgeTeam1 = 91,
            players = listOf(player(1, 0, 114), player(2, 0, 0), player(3, 0, null), player(4, 0, 110))))
        assertEquals(114, ranks.team0)
        assertEquals(91, ranks.team1)
        assertEquals(MatchRanks(null, null, null), matchRanks(MatchInfoDTO(players = listOf(Players(1)))))
    }

    @Test fun `notification shows the rank after the match and the match average`() {
        val text = MatchMessageGenerator.rankSummary(summary(badge = 114, delta = 300),
            details(player(42, 0, 111), player(2, 0, 111), player(3, 1, 121), player(4, 1, 121)), names)!!
        assertContains(text, "**R114** after this match (+300 progress)")
        assertContains(text, "Match average: **R114** (Amber R111 · Sapphire R121)")
    }

    @Test fun `basic notification has the player's rank before metadata arrives`() {
        assertEquals("**R114** after this match (-300 progress)", MatchMessageGenerator.rankSummary(summary(badge = 114, delta = -300), null, names))
    }

    @Test fun `metadata alone gives the rank they entered at`() {
        val text = MatchMessageGenerator.rankSummary(summary(), details(player(42, 0, 111, 70300, 70600)), names)!!
        assertContains(text, "Entered at **R111** (+300 progress)")
    }

    @Test fun `placement and demotion protection are stated and other modes have no rank field`() {
        assertEquals("Placement match", MatchMessageGenerator.rankSummary(summary(matchMode = 8, calibration = 1), null, names))
        assertContains(MatchMessageGenerator.rankSummary(summary(badge = 114, protection = true), null, names)!!, "Demotion protection used")
        assertNull(MatchMessageGenerator.rankSummary(summary(matchMode = 1, badge = 114), null, names))
        assertNull(MatchMessageGenerator.rankSummary(summary(gameMode = 4, badge = 114), null, names))
        assertNull(MatchMessageGenerator.rankSummary(summary(), null, names))
    }

    @Test fun `embed adds the rank field only when there is something to show`() {
        fun embed(match: MatchHistoryDTO) = MatchMessageGenerator.buildRecentMatchEmbed(match, "Player", null,
            { HeroInfo("Haze", null) }, names) { _, _ -> null }
        assertEquals("**R114** after this match", embed(summary(badge = 114)).fields.single { it.name == "🏅 Rank" }.value)
        assertTrue(embed(summary()).fields.none { it.name == "🏅 Rank" })
    }

    @Test fun `GC history keeps rank fields and leaves absent ones unknown`() {
        val row = Match.newBuilder().setMatchId(7).setHeroId(13).setMatchDurationS(100).setStartTime(1).setPlayerMatchOutcome(1)
        val ranked = row.clone().setRankedDisplayBadge(114).setRankedDelta(-300).setRankedCalibrationMatch(1).setRankedUsedDemotionProtection(true).build().toSummary("42")!!
        assertEquals(114, ranked.rankedDisplayBadge)
        assertEquals(-300, ranked.rankedDelta)
        assertEquals(1, ranked.rankedCalibrationMatch)
        assertEquals(true, ranked.rankedUsedDemotionProtection)
        assertNull(row.build().toSummary("42")!!.rankedDisplayBadge)
    }

    @Test fun `Valve metadata keeps each player's rank data`() {
        val rank = gc.citadel.CitadelMetadata.CMsgMatchPlayerRankData.newBuilder().setInitialDisplayRank(111)
            .setInitialFlatProgress(70300).setFinalFlatProgress(70600).setConsumedDemotionProtection(true)
        val info = Contents.MatchInfo.newBuilder().setMatchId(7).setStartTime(1).setDurationS(100).setWinningTeam(1)
            .addPlayers(Contents.Players.newBuilder().setAccountId(42).setHeroId(13).setPlayerRankData(rank))
        val bytes = CMsgMatchMetaData.newBuilder().setVersion(1).setMatchId(7)
            .setMatchDetails(Contents.newBuilder().setMatchInfo(info).build().toByteString()).build().toByteArray()
        assertEquals(PlayerRankData(111, 70300, 70600, true), decodeValveMetadata(bytes, 7).matchInfo.players.single().rankData)
    }

    @Test fun `public API rank fields decode from a real response shape`() {
        val json = Json { ignoreUnknownKeys = true }
        val history = json.decodeFromString<MatchHistoryDTO>("""{"account_id":366829568,"match_id":128238830,"hero_id":64,"hero_level":34,
            "start_time":1793420703,"game_mode":1,"match_mode":4,"player_team":1,"player_kills":16,"player_deaths":6,"player_assists":21,
            "denies":3,"net_worth":44844,"last_hits":163,"abandoned_time_s":0,"match_duration_s":2308,"match_result":1,
            "objectives_mask_team0":8260,"objectives_mask_team1":61125,"brawl_score_team0":null,"player_match_outcome":1,
            "ranked_display_badge":114,"ranked_delta":300,"ranked_calibration_match":0,"ranked_used_demotion_protection":false}""")
        assertEquals(114, history.rankedDisplayBadge)
        assertEquals(300, history.rankedDelta)
        val metadata = json.decodeFromString<Players>("""{"account_id":1,"mvp_rank":null,"player_rank_data":{"initial_display_rank":111,
            "initial_flat_progress":70300,"final_flat_progress":70600,"desired_progress_change":300,"initial_calibration_games":0,
            "initial_demotion_protection_games":3,"consumed_demotion_protection":false,"initial_win_streak":0}}""")
        assertEquals(PlayerRankData(111, 70300, 70600, false), metadata.rankData)
    }

    private fun rankClient(status: HttpStatusCode, body: String) = DeadlockClient(HttpClient(MockEngine {
        assertEquals("/v1/players/42/rank", it.url.encodedPath)
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }, 0)

    @Test fun `player rank reads the badge and treats failures as unavailable`() = runTest {
        val real = """{"badge":114,"rank":11,"subrank":4,"last_match":{"match_id":128238830,"player_rank_initial_display_rank":114}}"""
        assertEquals(114, rankClient(HttpStatusCode.OK, real).getPlayerRank("42"))
        assertEquals(0, rankClient(HttpStatusCode.OK, """{"badge":0,"rank":0,"subrank":0,"last_match":null}""").getPlayerRank("42"))
        assertNull(rankClient(HttpStatusCode.NotFound, "{}").getPlayerRank("42"))
    }

    @Test fun `profile prefers Valve's card and only then the public API`() = runTest {
        assertEquals(ProfileRank(74, false), resolveProfileRank(74) { fail("the card already has a rank") })
        assertEquals(ProfileRank(114, true), resolveProfileRank(null) { 114 })
        assertEquals(ProfileRank(null, false), resolveProfileRank(null) { 0 })
        assertEquals(ProfileRank(null, false), resolveProfileRank(null) { null })
    }

    @Test fun `profile labels a rank from the public API`() {
        val card = ProfileCard(107, null, emptyList())
        val rank = RankRepository.describeBadge(114, listOf(Rank(11, "Eternus")))
        val embed = LiveMessageGenerator.profileEmbed("Player", card, rank, { "Hero" }, 114, true)
        assertContains(embed.description!!, "**Rank:** Eternus 4 (as of their latest ranked match)")
        assertContains(LiveMessageGenerator.profileEmbed("Player", card, null, { "Hero" }, 114, true).description!!, "Badge 114")
        assertContains(LiveMessageGenerator.profileEmbed("Player", card, null, { "Hero" }).description!!, "Unranked or hidden")
    }

    @Test fun `rank fields survive storage and a deployed database gains the columns`() {
        Database.connect("jdbc:h2:mem:rank-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction {
            SchemaUtils.create(CachedMatchTable)
            // The table as already deployed, before the rank columns existed.
            exec("""CREATE TABLE cached_player_matches (account_id VARCHAR(50) NOT NULL, match_id BIGINT NOT NULL REFERENCES cached_matches(match_id),
                hero_id INT NOT NULL, team INT NOT NULL, match_result INT NOT NULL, kills INT NOT NULL, deaths INT NOT NULL, assists INT NOT NULL,
                net_worth INT NOT NULL, hero_level INT NOT NULL, last_hits INT NOT NULL, denies INT NOT NULL, abandoned_time_s INT NULL,
                team_abandoned BOOLEAN NULL, PRIMARY KEY (account_id, match_id))""")
            exec("INSERT INTO cached_matches (match_id, start_time, duration_s, game_mode, match_mode, objectives_mask_team0, objectives_mask_team1) VALUES (6, 1, 100, 1, 4, 0, 0)")
            exec("INSERT INTO cached_player_matches (account_id, match_id, hero_id, team, match_result, kills, deaths, assists, net_worth, hero_level, last_hits, denies) VALUES ('42', 6, 13, 0, 0, 1, 1, 1, 1, 1, 1, 1)")
            addRankColumns()
            addRankColumns() // Runs on every startup.
        }
        assertNull(MatchCacheRepository.find("42", 6)!!.rankedDisplayBadge, "existing rows stay readable")
        val stored = summary(badge = 114, delta = 300, calibration = 0, protection = false)
        MatchCacheRepository.save("42", listOf(stored))
        assertEquals(stored, MatchCacheRepository.find("42", 7))
    }
}
