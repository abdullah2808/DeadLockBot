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

    // Steam bot account for the Deadlock Game Coordinator (Phase B). Optional:
    // when unset, the scheduler falls back to the active-feed match source.
    private fun optional(key: String): String? =
        (dotenv?.get(key) ?: System.getenv(key))?.takeIf { it.isNotBlank() }

    val STEAM_USERNAME: String? = optional("STEAM_USERNAME")
    val STEAM_PASSWORD: String? = optional("STEAM_PASSWORD")

    // A previously-issued refresh token. Preferred for headless/deployed runs so
    // no interactive Steam Guard prompt is needed. See GcClient for how the token
    // is obtained and persisted on first login.
    val STEAM_TOKEN_FILE: String = optional("STEAM_TOKEN_FILE") ?: "steam_refresh_token.txt"

    val STEAM_REFRESH_TOKEN: String? = optional("STEAM_REFRESH_TOKEN")
    val STEAM_METADATA_INTERVAL_SECONDS: Long = optional("STEAM_METADATA_INTERVAL_SECONDS")?.let {
        it.toLongOrNull()?.takeIf { seconds -> seconds in 0..86_400 }
            ?: error("STEAM_METADATA_INTERVAL_SECONDS must be between 0 and 86400")
    } ?: 1800L
    val STEAM_AUTO_ACCEPT_FRIENDS: Boolean = optional("STEAM_AUTO_ACCEPT_FRIENDS")?.let {
        it.lowercase().toBooleanStrictOrNull() ?: error("STEAM_AUTO_ACCEPT_FRIENDS must be true or false")
    } ?: true

    // Optional deadlock-api.com key: raises its Steam-backed metadata quota from 3 to 300 lookups per hour.
    val DEADLOCK_API_KEY: String? = optional("DEADLOCK_API_KEY")
    // Let deadlock-api fetch missing matches from Steam with its own quota before this bot spends a GC query.
    val DEADLOCK_API_STEAM_FALLBACK: Boolean = optional("DEADLOCK_API_STEAM_FALLBACK")?.let {
        it.lowercase().toBooleanStrictOrNull() ?: error("DEADLOCK_API_STEAM_FALLBACK must be true or false")
    } ?: true
}
