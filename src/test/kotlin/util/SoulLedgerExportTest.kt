package util

import kotlinx.serialization.json.*
import models.*
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.test.*

class SoulLedgerExportTest {
    private val catalog = mapOf(1L to ItemInfo(1, "Item", type = "upgrade"), 2L to ItemInfo(2, "Ability", type = "ability"))
    @Test fun `exports final inventory excludes abilities sold items and account identity`() {
        val player = Players(9876, heroId = 8, name = "private name", items = listOf(
            MatchItem(1), MatchItem(2), MatchItem(3, soldTimeSeconds = 10), MatchItem(1), MatchItem(4294967295)))
        val url = SoulLedgerExport.url(player, 123, catalog)!!
        val payload = Json.parseToJsonElement(URLDecoder.decode(url.substringAfter("#build="), StandardCharsets.UTF_8)).jsonObject
        assertEquals(1, payload["v"]!!.jsonPrimitive.int)
        assertEquals(8, payload["hero"]!!.jsonPrimitive.int)
        assertEquals("123", payload["match"]!!.jsonPrimitive.content)
        assertEquals(listOf(1L, 4294967295L), payload["items"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertFalse(url.contains("9876")); assertFalse(url.contains("private"))
    }
    @Test fun `missing data and unsupported inventory have no misleading export link`() {
        assertNull(SoulLedgerExport.url(Players(1, heroId = 8), 123, catalog))
        assertNull(SoulLedgerExport.url(Players(1, heroId = 8, items = emptyList()), 123, emptyMap()))
        assertNull(SoulLedgerExport.url(Players(1, heroId = 8, items = (10L..22).map { MatchItem(it) }), 123, catalog))
        assertNotNull(SoulLedgerExport.url(Players(1, heroId = 8, items = emptyList()), 123, catalog))
    }
}
