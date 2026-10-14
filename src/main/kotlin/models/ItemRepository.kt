package models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.URI

@Serializable
data class ItemInfo(
    val id: Long,
    val name: String,
    val type: String? = null,
    @SerialName("shop_image") val shopImage: String? = null,
    val image: String? = null,
    @SerialName("class_name") val className: String? = null,
    val description: JsonObject? = null,
    val properties: JsonObject? = null
)

object ItemRepository {
    private var cachedAt = 0L
    private var cached: Map<Long, ItemInfo> = emptyMap()

    @Synchronized
    fun getItems(): Map<Long, ItemInfo> {
        val now = System.currentTimeMillis()
        if (now - cachedAt > 6 * 60 * 60 * 1000L) {
            // Cache failures briefly too, so an asset outage does not delay every request.
            cachedAt = now
            try {
                val connection = URI("https://api.deadlock-api.com/v1/assets/items").toURL().openConnection().apply {
                    connectTimeout = 3_000
                    readTimeout = 3_000
                }
                val body = connection.getInputStream().bufferedReader().use { it.readText() }
                cached = Json { ignoreUnknownKeys = true }.decodeFromString<List<ItemInfo>>(body).associateBy { it.id }
            } catch (e: Exception) {
                cachedAt = now - 6 * 60 * 60 * 1000L + 60_000
                println("Item assets unavailable: ${e.message}")
            }
        }
        return cached
    }
}
