package models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URL

@Serializable
data class HeroImages(
    @SerialName("minimap_image") val minimapImage: String? = null
)

@Serializable
data class Hero(
    val id: Int,
    val name: String,
    val images: HeroImages? = null
)

data class HeroInfo(
    val name: String,
    val minimapImage: String?
)

object HeroRepository {
    private const val cacheTtlMillis: Long = 6 * 60 * 60 * 1000L // 6 hours
    private var cachedAtMillis: Long = 0L
    private var cachedHeroMap: Map<Int, HeroInfo> = emptyMap()

    private fun loadHeroMap(): Map<Int, HeroInfo> {
        val json = URL("https://assets.deadlock-api.com/v2/heroes").readText()
        val heroes = Json {
            ignoreUnknownKeys = true
        }.decodeFromString<List<Hero>>(json)
        return heroes.associate { hero ->
            hero.id to HeroInfo(
                name = hero.name,
                minimapImage = hero.images?.minimapImage
            )
        }
    }

    private fun getHeroMap(): Map<Int, HeroInfo> {
        val now = System.currentTimeMillis()
        val isExpired = now - cachedAtMillis > cacheTtlMillis
        if (cachedHeroMap.isEmpty() || isExpired) {
            try {
                cachedHeroMap = loadHeroMap()
                cachedAtMillis = now
            } catch (e: Exception) {
                println("HeroRepository refresh failed: ${e.message}")
                if (cachedHeroMap.isEmpty()) {
                    return emptyMap()
                }
            }
        }
        return cachedHeroMap
    }

    fun getHeroName(id: Int): String = getHeroMap()[id]?.name ?: "Unknown Hero ($id)"
    fun getHeroMinimapImage(id: Int): String? = getHeroMap()[id]?.minimapImage
}
