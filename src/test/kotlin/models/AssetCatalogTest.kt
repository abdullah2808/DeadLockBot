package models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AssetCatalogTest {
    @Test
    fun `missing hero refreshes before the six hour expiry`() {
        var now = 0L
        var loads = 0
        val catalog = AssetCatalog(
            load = {
                loads++
                if (loads == 1) mapOf(14 to "Holliday") else mapOf(14 to "Holliday", 88 to "Baba")
            },
            clock = { now },
        )

        assertEquals("Holliday", catalog.get(14))
        now = 60_000L
        assertEquals("Baba", catalog.get(88))
        assertEquals(2, loads)
    }

    @Test
    fun `unknown IDs cannot repeatedly refresh the catalog`() {
        var now = 0L
        var loads = 0
        val catalog = AssetCatalog(load = { loads++; mapOf(14 to "Holliday") }, clock = { now })

        assertNull(catalog.get(88))
        assertNull(catalog.get(85))
        assertEquals(1, loads)
        now = 60_000L
        assertNull(catalog.get(88))
        assertNull(catalog.get(88))
        assertEquals(2, loads)
    }

    @Test
    fun `failed initial load is retried after a cooldown`() {
        var now = 0L
        var loads = 0
        val catalog = AssetCatalog(
            load = {
                if (++loads == 1) error("API offline")
                mapOf(85 to "Solomon")
            },
            clock = { now },
            onFailure = {},
        )

        assertNull(catalog.get(85))
        assertNull(catalog.get(85))
        assertEquals(1, loads)
        now = 60_000L
        assertEquals("Solomon", catalog.get(85))
    }

    @Test
    fun `failed expired refresh preserves the last good catalog`() {
        var now = 0L
        var loads = 0
        val catalog = AssetCatalog(
            load = { if (++loads > 1) error("API offline") else mapOf(14 to "Holliday") },
            clock = { now },
            onFailure = {},
        )

        assertEquals("Holliday", catalog.get(14))
        now = 6 * 60 * 60 * 1000L
        assertEquals("Holliday", catalog.get(14))
        assertEquals("Holliday", catalog.get(14))
        assertEquals(2, loads)
    }

    @Test
    fun `expired catalog refreshes existing entries`() {
        var now = 0L
        var loads = 0
        val catalog = AssetCatalog(load = { mapOf(14 to "Name ${++loads}") }, clock = { now })

        assertEquals("Name 1", catalog.get(14))
        now = 6 * 60 * 60 * 1000L
        assertEquals("Name 2", catalog.get(14))
    }

    @Test
    fun `empty upstream response preserves existing entries`() {
        var now = 0L
        var loads = 0
        val catalog = AssetCatalog(
            load = { if (++loads == 1) mapOf(14 to "Holliday") else emptyMap() },
            clock = { now },
            onFailure = {},
        )

        assertEquals("Holliday", catalog.get(14))
        now = 60_000L
        assertNull(catalog.get(88))
        assertEquals("Holliday", catalog.get(14))
    }

    @Test
    fun `current hero response includes new heroes and tolerates unrelated fields`() {
        val heroes = assetJson.decodeFromString<List<Hero>>("""
            [
              {"id":14,"name":"Holliday","development_state":"release"},
              {"id":85,"name":"Solomon","images":{"minimap_image":"https://example.test/solomon.png","name_image":"ignored"}},
              {"id":88,"name":"Baba","images":null}
            ]
        """.trimIndent())

        assertEquals(listOf("Holliday", "Solomon", "Baba"), heroes.map { it.name })
        assertEquals("https://example.test/solomon.png", heroes[1].images?.minimapImage)
        assertNull(heroes[2].images)
    }
}
