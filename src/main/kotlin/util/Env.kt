import io.github.cdimascio.dotenv.dotenv

object Env {
    private val dotenv = try {
        // Loads from .env if present (local dev). Be lenient: a single malformed
        // line (e.g. `KEY: value` instead of `KEY=value`) or a missing file must
        // not abort the whole load — otherwise one bad line makes an unrelated
        // var like DISCORD_TOKEN look "missing". Bad lines are skipped and we
        // fall back to real environment variables.
        dotenv {
            ignoreIfMalformed = true
            ignoreIfMissing = true
        }
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
    val STEAM_REFRESH_TOKEN: String? = optional("STEAM_REFRESH_TOKEN")
}