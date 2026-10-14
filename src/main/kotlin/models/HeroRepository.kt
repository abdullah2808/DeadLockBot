package models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HeroImages(
    @SerialName("minimap_image") val minimapImage: String? = null
)

@Serializable
data class Hero(
    val id: Int,
    val name: String,
    val images: HeroImages? = null,
    val items: Map<String, String> = emptyMap()
)

data class HeroInfo(
    val name: String,
    val minimapImage: String?,
    val abilities: List<String> = emptyList()
)

object HeroRepository {
    private val catalog = AssetCatalog(load = {
        assetJson.decodeFromString<List<Hero>>(readAssetCatalog("heroes")).associate { hero ->
            hero.id to HeroInfo(
                name = hero.name,
                minimapImage = hero.images?.minimapImage,
                abilities = (1..4).mapNotNull { hero.items["signature$it"] }
            )
        }
    })

    fun getHeroName(id: Int): String = catalog.get(id)?.name ?: "Unknown Hero ($id)"
    fun getHeroMinimapImage(id: Int): String? = catalog.get(id)?.minimapImage
    fun getHeroInfo(id: Int): HeroInfo = catalog.get(id) ?: HeroInfo("Unknown Hero ($id)", null)
}
