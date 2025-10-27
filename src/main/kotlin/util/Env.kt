import io.github.cdimascio.dotenv.dotenv

object Env {
    private val dotenv = try {
        dotenv()  // Loads from .env if available (for local dev)
    } catch (e: Exception) {
        null
    }

    // Discord token
    val DISCORD_TOKEN: String = dotenv?.get("DISCORD_TOKEN")
        ?: System.getenv("DISCORD_TOKEN")
        ?: error("Missing DISCORD_TOKEN")

    // Database
    val DB_URL: String = dotenv?.get("DB_URL")
        ?: System.getenv("DB_URL")
        ?: error("Missing DB_URL")

    val DB_USER: String = dotenv?.get("DB_USER")
        ?: System.getenv("DB_USER")
        ?: error("Missing DB_USER")

    val DB_PASSWORD: String = dotenv?.get("DB_PASSWORD")
        ?: System.getenv("DB_PASSWORD")
        ?: error("Missing DB_PASSWORD")
}