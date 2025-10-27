package models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URL

@Serializable
data class Hero(
    val id: Int,
    val name: String
)

object HeroRepository {
    private val heroMap: Map<Int, String> by lazy {
        val json = URL("https://assets.deadlock-api.com/v2/heroes").readText()
        val heroes = Json.decodeFromString<List<Hero>>(json)
        heroes.associate { it.id to it.name }
    }

    fun getHeroName(id: Int): String = heroMap[id] ?: "Unknown Hero ($id)"
}