package util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import models.HeroInfo
import models.HeroRepository
import models.ItemRepository
import models.MatchDTO
import java.awt.image.BufferedImage
import java.net.URI
import javax.imageio.ImageIO

object ScoreboardAssetLoader {
    private data class CachedImage(val image: BufferedImage?, val loadedAt: Long)
    private val cache = object : LinkedHashMap<String, CachedImage>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedImage>?): Boolean = size > 256
    }
    private val downloads = Semaphore(8)

    suspend fun load(match: MatchDTO): ScoreboardAssets = withContext(Dispatchers.IO) {
        val heroIds = match.matchInfo.players.map { it.heroId }.distinct()
        val heroes = heroIds.associateWith { HeroInfo(HeroRepository.getHeroName(it), HeroRepository.getHeroMinimapImage(it)) }
        val items = ItemRepository.getItems()
        val shopItemIds = items.values.filter { it.type == "upgrade" }.map { it.id }.toSet().takeIf { it.isNotEmpty() }
        val itemIds = match.matchInfo.players.flatMap { it.finalItems.filter { item -> shopItemIds?.contains(item.itemId) == true }.take(24) }
            .map { it.itemId }.distinct()
        coroutineScope {
            val portraits = heroIds.map { id -> async { id to loadImage(heroes[id]?.minimapImage) } }
            val icons = itemIds.map { id -> async { id to loadImage(items[id]?.let { it.shopImage ?: it.image }) } }
            ScoreboardAssets(
                heroes,
                portraits.awaitAll().mapNotNull { (id, image) -> image?.let { id to it } }.toMap(),
                icons.awaitAll().mapNotNull { (id, image) -> image?.let { id to it } }.toMap(),
                shopItemIds
            )
        }
    }

    private suspend fun loadImage(url: String?): BufferedImage? {
        if (url.isNullOrBlank()) return null
        val now = System.currentTimeMillis()
        synchronized(cache) { cache[url]?.let { if (it.image != null || now - it.loadedAt < 60_000) return it.image } }
        return downloads.withPermit {
            val image = try {
                val uri = URI(url)
                if (uri.scheme != "https" || uri.host != "assets-bucket.deadlock-api.com") return@withPermit null
                val connection = uri.toURL().openConnection().apply { connectTimeout = 2_000; readTimeout = 2_000 }
                connection.getInputStream().use { ImageIO.read(it) }
            } catch (_: Exception) { null }
            synchronized(cache) { cache[url] = CachedImage(image, now) }
            image
        }
    }
}
