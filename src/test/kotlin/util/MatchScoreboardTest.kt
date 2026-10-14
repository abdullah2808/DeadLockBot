package util

import kotlinx.serialization.json.Json
import models.HeroInfo
import models.MatchDTO
import models.MatchInfoDTO
import models.MatchItem
import models.Players
import models.Stats
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MatchScoreboardTest {
    @Test
    fun `decode real metadata with nullable damage fields`() {
        val body = javaClass.getResource("/match-metadata.json")!!.readText()
        val match = Json { ignoreUnknownKeys = true }.decodeFromString<MatchDTO>(body)
        assertEquals(103328445L, match.matchInfo.matchId)
        assertEquals(2, match.matchInfo.players.size)
        assertEquals(25575, match.matchInfo.players.first().finalStats?.playerDamage)
        assertNotNull(decodeImage(match))
    }

    @Test
    fun `equal timestamps select the last recorded snapshot`() {
        val player = Players(42, stats = listOf(Stats(playerDamage = 10), Stats(playerDamage = 20)))
        assertEquals(20, player.finalStats?.playerDamage)
    }

    @Test
    fun `inventory excludes ability unlocks and sold shop items`() {
        val greenIcon = java.awt.image.BufferedImage(34, 34, java.awt.image.BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().let { g -> try { g.color = java.awt.Color.GREEN; g.fillRect(0, 0, 34, 34) } finally { g.dispose() } }
        }
        val player = Players(42, team = 0, items = listOf(MatchItem(1), MatchItem(2, soldTimeSeconds = 10), MatchItem(3)))
        val png = MatchScoreboard.render(MatchDTO(MatchInfoDTO(players = listOf(player))), 1,
            ScoreboardAssets(items = mapOf(3L to greenIcon), shopItemIds = setOf(2, 3)))
        val image = ImageIO.read(ByteArrayInputStream(png))
        assertEquals(java.awt.Color.GREEN.rgb, image.getRGB(1235, 125 + 79 + 12 + 26))
    }

    @Test
    fun `decode partial metadata and preserve unavailable stats`() {
        val match = Json { ignoreUnknownKeys = true }.decodeFromString<MatchDTO>("""
            {"match_info":{"players":[{"account_id":42,"team":0,"hero_id":1,
                "stats":[{"time_stamp_s":10,"player_damage":300},{"time_stamp_s":5,"player_damage":100}],
                "items":[{"item_id":10,"sold_time_s":20},{"item_id":20,"sold_time_s":0},{"item_id":30}]}]}}
        """.trimIndent())
        val player = match.matchInfo.players.single()
        assertEquals(300, player.finalStats?.playerDamage)
        assertEquals(null, player.finalStats?.playerHealing)
        assertEquals(listOf(20L, 30L), player.finalItems.map { it.itemId })
    }

    @Test
    fun `render twelve players with unavailable stats and unknown teams`() {
        val players = List(12) { i -> Players(i, team = i / 6, heroId = i + 1, playerSlot = i) }
        val normal = decodeImage(MatchDTO(MatchInfoDTO(players = players)))
        assertEquals(1800, normal.width)
        assertTrue(normal.height > 1000)
        val unknown = decodeImage(MatchDTO(MatchInfoDTO(players = players + Players(99, team = 16))))
        assertTrue(unknown.height > normal.height)
    }

    @Test
    fun `render wide inventories and long names without crashing`() {
        val player = Players(42, team = 0, name = "Long name\n".repeat(80), items = List(24) { MatchItem(it + 1L) })
        val png = MatchScoreboard.render(MatchDTO(MatchInfoDTO(players = listOf(player))), 12345678,
            ScoreboardAssets(shopItemIds = (1L..24L).toSet()))
        assertNotNull(ImageIO.read(ByteArrayInputStream(png)))
    }

    @Test
    fun `write a representative preview`() {
        val names = listOf("Lady Geist", "Haze", "Bebop", "Abrams", "Calico", "Paradox", "Wraith", "Seven", "Shiv", "Ivy", "Mo & Krill", "Dynamo")
        val heroes = names.mapIndexed { i, name -> i + 1 to HeroInfo(name, null) }.toMap()
        val players = names.mapIndexed { i, _ ->
            Players(100000 + i, team = i / 6, heroId = i + 1, playerSlot = i,
                name = listOf("Kitty", "Risky", "Komodo", "Nael", "Echo", "Koi", "Monkey", "Moroka", "Butcher", "Tiarus", "Isla", "Rain")[i],
                kills = 7 + i, deaths = 3 + i % 7, assists = 15 + i, netWorth = 42000 + i * 2300,
                stats = listOf(Stats(30000 + i * 1400, 5000 + i * 700, 600 + i * 330, timeStampSeconds = 2210)),
                items = List(12) { MatchItem(it + 1L) })
        }
        val png = MatchScoreboard.render(MatchDTO(MatchInfoDTO(players = players, winningTeam = 0, matchOutcome = 0, durationSeconds = 2210)),
            12345678, ScoreboardAssets(heroes = heroes, shopItemIds = (1L..12L).toSet()), 100002)
        File("build/scoreboard-preview.png").apply { parentFile.mkdirs(); writeBytes(png) }
        assertNotNull(ImageIO.read(ByteArrayInputStream(png)))
    }

    private fun decodeImage(match: MatchDTO) = ImageIO.read(ByteArrayInputStream(MatchScoreboard.render(match, 12345678)))
}
