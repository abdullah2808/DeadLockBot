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
    private val heroMap: Map<Int, HeroInfo> by lazy {
        val json = URL("https://assets.deadlock-api.com/v2/heroes").readText()
        val heroes = Json {
            ignoreUnknownKeys = true
        }.decodeFromString<List<Hero>>(json)
        heroes.associate { hero ->
            hero.id to HeroInfo(
                name = hero.name,
                minimapImage = hero.images?.minimapImage
            )
        }
    }

    fun getHeroName(id: Int): String = heroMap[id]?.name ?: "Unknown Hero ($id)"
    fun getHeroMinimapImage(id: Int): String? = heroMap[id]?.minimapImage
}