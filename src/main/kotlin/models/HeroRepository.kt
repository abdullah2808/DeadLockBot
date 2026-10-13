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
    val images: HeroImages? = null
)

data class HeroInfo(
    val name: String,
    val minimapImage: String?
)

object HeroRepository {
    private val catalog = AssetCatalog(load = {
        assetJson.decodeFromString<List<Hero>>(readAssetCatalog("heroes")).associate { hero ->
            hero.id to HeroInfo(
                name = hero.name,
                minimapImage = hero.images?.minimapImage
            )
        }
    })

    fun getHeroName(id: Int): String = catalog.get(id)?.name ?: "Unknown Hero ($id)"
    fun getHeroMinimapImage(id: Int): String? = catalog.get(id)?.minimapImage
}
