package util

import java.net.URI

/** Numeric inputs resolve locally; /id/ profile names require a Steam lookup. */
object AccountId {
    private const val STEAM64_BASE = 76561197960265728L
    private const val MAX_ACCOUNT_ID = 4294967295L
    private val digits = Regex("[0-9]+")
    private val profilePath = Regex("/(profiles|id)/([A-Za-z0-9_.-]+)/?")

    fun normalize(raw: String): String? {
        val value = raw.trim().removeSurrounding("<", ">")
        if (digits.matches(value)) return normalizeNumber(value)
        val profile = profile(value) ?: return null
        if (profile.groupValues[1] != "profiles") return null
        val steamId = profile.groupValues[2]
        return steamId.takeIf { it.length == 17 }?.let(::normalizeNumber)
    }

    fun vanityName(raw: String): String? = profile(raw.trim().removeSurrounding("<", ">"))
        ?.takeIf { it.groupValues[1] == "id" }
        ?.groupValues?.get(2)

    private fun normalizeNumber(value: String): String? {
        if (!digits.matches(value)) return null
        val number = value.toLongOrNull() ?: return null
        val account = if (value.length == 17) number - STEAM64_BASE else number
        return account.takeIf { it in 1..MAX_ACCOUNT_ID }?.toString()
    }

    private fun profile(raw: String): MatchResult? {
        val withScheme = if (raw.startsWith("steamcommunity.com/", ignoreCase = true) ||
            raw.startsWith("www.steamcommunity.com/", ignoreCase = true)) "https://$raw" else raw
        val uri = try { URI(withScheme) } catch (_: Exception) { return null }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        if (uri.host?.lowercase() !in setOf("steamcommunity.com", "www.steamcommunity.com") ||
            uri.rawUserInfo != null ||
            (uri.port != -1 && uri.port != if (scheme == "https") 443 else 80)) return null
        return profilePath.matchEntire(uri.rawPath ?: "")
    }
}
