package util

/**
 * Normalizes the various ways a player might supply their Deadlock account:
 *  - the numeric Steam3 account id (what the API expects),
 *  - a 17-digit SteamID64,
 *  - or a full Steam profile URL (…/profiles/7656119XXXXXXXXXX).
 *
 * Returns the canonical Steam3 account id as a string, or null if the input
 * can't be interpreted as a valid account.
 */
object AccountId {
    // Steam converts between SteamID64 and the 32-bit account id with this offset.
    private const val STEAM64_BASE = 76561197960265728L

    private val profileUrlRegex = Regex("""(?:profiles/)?(\d{17})""")

    fun normalize(raw: String): String? {
        val trimmed = raw.trim()

        // Pull a 17-digit SteamID64 out of a profile URL if one is present.
        val extracted = profileUrlRegex.find(trimmed)?.groupValues?.get(1)
        val value = extracted ?: trimmed

        if (value.isEmpty() || value.any { !it.isDigit() }) return null

        val numeric = value.toLongOrNull() ?: return null

        val accountId = if (value.length == 17 && numeric > STEAM64_BASE) {
            numeric - STEAM64_BASE
        } else {
            numeric
        }

        return if (accountId > 0) accountId.toString() else null
    }
}
