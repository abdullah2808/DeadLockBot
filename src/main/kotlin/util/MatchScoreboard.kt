package util

import models.HeroInfo
import models.MatchDTO
import models.Players
import models.ItemInfo
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.text.NumberFormat
import java.util.Locale
import javax.imageio.ImageIO

data class ScoreboardAssets(
    val heroes: Map<Int, HeroInfo> = emptyMap(),
    val portraits: Map<Int, BufferedImage> = emptyMap(),
    val items: Map<Long, BufferedImage> = emptyMap(),
    val shopItemIds: Set<Long>? = null,
    val catalog: Map<Long, ItemInfo> = emptyMap()
)

/** Pure Java2D renderer: works in headless deployments without a browser or image service. */
object MatchScoreboard {
    private val ink = Color(233, 235, 240)
    private val muted = Color(157, 168, 184)
    private val amber = Color(231, 183, 91)
    private val sapphire = Color(107, 172, 239)
    private const val width = 1800
    private const val rowHeight = 86

    fun render(match: MatchDTO, matchId: Long, assets: ScoreboardAssets = ScoreboardAssets(), highlightedAccount: Int? = null): ByteArray {
        val info = match.matchInfo
        val groups = info.players.groupBy { it.team }
        val teams = listOf(0, 1) + groups.keys.filter { it != 0 && it != 1 }.sorted()
        val height = 205 + teams.sumOf { 100 + maxOf(1, groups[it]?.size ?: 0) * rowHeight }
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.paint = GradientPaint(0f, 0f, Color(21, 26, 36), width.toFloat(), height.toFloat(), Color(9, 12, 18))
            g.fillRect(0, 0, width, height)
            text(g, "DEADLOCK", 40, 51, 30, amber, true)
            text(g, "MATCH SCOREBOARD", 40, 93, 36, ink, true)
            val duration = info.durationSeconds?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" } ?: "Unavailable"
            right(g, "MATCH $matchId   /   DURATION $duration", width - 40, 62, 22, muted)
            var y = 125
            for (team in teams) {
                val players = groups[team].orEmpty().sortedBy { it.playerSlot }
                val tint = when (team) { 0 -> amber; 1 -> sapphire; else -> muted }
                val name = when (team) { 0 -> "THE AMBER HAND"; 1 -> "THE SAPPHIRE FLAME"; else -> "OTHER / UNKNOWN TEAM" }
                val result = when {
                    info.matchOutcome == 2 -> "DRAW"
                    info.matchOutcome == 1 -> "NO CONTEST"
                    info.winningTeam !in listOf(0, 1) -> "RESULT UNAVAILABLE"
                    team == info.winningTeam -> "VICTORY"
                    team in listOf(0, 1) -> "DEFEAT"
                    else -> ""
                }
                g.color = Color(tint.red, tint.green, tint.blue, 24)
                g.fillRoundRect(32, y, width - 64, 52, 10, 10)
                text(g, "$name   /   $result", 48, y + 34, 25, tint, true)
                val total = players.sumOf { it.netWorth.toLong() }
                right(g, "TEAM SOULS  ${if (players.isNotEmpty()) number(total) else "—"}", width - 48, y + 34, 22, tint)
                y += 79
                text(g, "PLAYER / HERO", 48, y, 16, muted, true)
                val columns = listOf("SOULS" to 505, "K / D / A" to 690, "PLAYER DMG" to 865, "OBJ DMG" to 1030, "HEALING" to 1190)
                columns.forEach { (label, x) -> right(g, label, x, y, 16, muted) }
                text(g, "FINAL ITEMS", 1230, y, 16, muted, true)
                y += 12
                if (players.isEmpty()) {
                    text(g, "No player data available", 48, y + 48, 22, muted)
                    y += rowHeight
                }
                players.forEachIndexed { index, player ->
                    drawPlayer(g, player, assets, y, tint, index, player.accountId == highlightedAccount)
                    y += rowHeight
                }
                y += 9
            }
            text(g, "— = unavailable   •   Damage and healing use the final recorded snapshot   •   Outlined row = your account", 40, height - 28, 18, muted)
        } finally {
            g.dispose()
        }
        return ByteArrayOutputStream().use { ImageIO.write(image, "png", it); it.toByteArray() }
    }

    private fun drawPlayer(g: Graphics2D, player: Players, assets: ScoreboardAssets, y: Int, tint: Color, index: Int, highlighted: Boolean) {
        g.color = if (index % 2 == 0) Color(29, 35, 47) else Color(23, 29, 40)
        g.fillRoundRect(32, y, width - 64, rowHeight - 5, 8, 8)
        if (highlighted) { g.color = tint; g.drawRoundRect(33, y + 1, width - 66, rowHeight - 7, 8, 8) }
        val portrait = assets.portraits[player.heroId]
        if (portrait != null) g.drawImage(portrait, 48, y + 14, 54, 54, null)
        else { g.color = tint; g.fillRoundRect(48, y + 14, 54, 54, 8, 8); text(g, "?", 67, y + 51, 27, Color(20, 25, 32), true) }
        val hero = assets.heroes[player.heroId]?.name ?: "Hero ${player.heroId}"
        text(g, fit(g, hero, 284, 23), 118, y + 33, 23, ink, true)
        val label = player.name?.takeIf { it.isNotBlank() } ?: "Account ${Integer.toUnsignedString(player.accountId)}"
        text(g, fit(g, label, 284, 18), 118, y + 59, 18, muted)
        val stats = player.finalStats
        right(g, number(player.netWorth), 505, y + 46, 23, tint)
        val kda = listOf(player.kills, player.deaths, player.assists).joinToString(" / ")
        right(g, kda, 690, y + 46, 23, ink)
        right(g, number(stats?.playerDamage), 865, y + 46, 23, ink)
        right(g, number(stats?.objectiveDamage), 1030, y + 46, 23, ink)
        right(g, number(stats?.playerHealing), 1190, y + 46, 23, ink)
        // Item events also contain hero ability unlocks. Only shop upgrades belong here.
        val items = player.finalItems.filter { assets.shopItemIds?.contains(it.itemId) == true }
        // Wrap to two rows when a match mode has more than twelve inventory slots.
        items.take(24).forEachIndexed { i, item ->
            val x = 1230 + (i % 12) * 42
            val top = y + (if (items.size > 12) 4 else 21) + (i / 12) * 37
            g.color = Color(47, 58, 73)
            g.fillRoundRect(x, top, 34, 34, 5, 5)
            val icon = assets.items[item.itemId]
            if (icon != null) g.drawImage(icon, x, top, 34, 34, null)
            else text(g, "?", x + 11, top + 24, 19, muted)
        }
        if (items.isEmpty()) text(g, if (assets.shopItemIds == null || player.items == null) "Unavailable" else "—", 1230, y + 46, 20, muted)
    }

    private fun number(value: Int?): String = value?.let { number(it.toLong()) } ?: "—"
    private fun number(value: Long): String = NumberFormat.getIntegerInstance(Locale.US).format(value)
    private fun text(g: Graphics2D, value: String, x: Int, y: Int, size: Int, color: Color, bold: Boolean = false) {
        g.font = Font("SansSerif", if (bold) Font.BOLD else Font.PLAIN, size)
        g.color = color
        g.drawString(value, x, y)
    }
    private fun right(g: Graphics2D, value: String, x: Int, y: Int, size: Int, color: Color) {
        g.font = Font("SansSerif", Font.PLAIN, size)
        text(g, value, x - g.fontMetrics.stringWidth(value), y, size, color)
    }
    private fun fit(g: Graphics2D, value: String, maxWidth: Int, size: Int): String {
        g.font = Font("SansSerif", Font.BOLD, size)
        var clean = value.replace(Regex("[\\p{Cntrl}]"), " ").take(128)
        if (g.fontMetrics.stringWidth(clean) <= maxWidth) return clean
        while (clean.isNotEmpty() && g.fontMetrics.stringWidth("$clean…") > maxWidth) clean = clean.dropLast(1)
        return "$clean…"
    }
}
