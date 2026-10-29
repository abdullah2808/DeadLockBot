package util

import kotlinx.serialization.json.*
import models.ItemInfo
import models.Players
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Versioned, numeric-only fragment. No account identity or executable content leaves the report. */
object SoulLedgerExport {
    const val BASE_URL = "https://abdullah2808.github.io/Soul-Ledger/"
    fun url(player: Players, matchId: Long, catalog: Map<Long, ItemInfo>): String? {
        if (player.heroId <= 0 || matchId <= 0 || player.items == null || catalog.isEmpty()) return null
        val final = player.finalItems.filter { catalog[it.itemId]?.type != "ability" }.map { it.itemId }.distinct()
        if (final.size > 12 || final.any { it !in 1..4294967295L }) return null
        val payload = buildJsonObject {
            put("v", 1); put("hero", player.heroId); put("match", matchId.toString())
            put("items", JsonArray(final.map(::JsonPrimitive)))
        }
        return BASE_URL + "#build=" + URLEncoder.encode(payload.toString(), StandardCharsets.UTF_8)
    }
}
