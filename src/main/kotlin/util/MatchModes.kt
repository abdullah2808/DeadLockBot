package util

/** Labels for Deadlock's ECitadelGameMode / ECitadelMatchMode pair, shared by every match embed. */
object MatchModes {
    private const val GAME_MODE_STREET_BRAWL = 4
    private const val MATCH_MODE_RANKED = 4
    private const val MATCH_MODE_PLACEMENT = 8
    const val MATCH_MODE_STANDARD = 1

    fun isStreetBrawl(gameMode: Int) = gameMode == GAME_MODE_STREET_BRAWL

    fun label(gameMode: Int, matchMode: Int): String? = when {
        // Street Brawl is a separate game mode; its match mode is not what players think of as the queue.
        gameMode == GAME_MODE_STREET_BRAWL -> "🥊 Street Brawl"
        matchMode == MATCH_MODE_RANKED -> "🏅 Ranked"
        matchMode == MATCH_MODE_STANDARD -> "🎮 Standard"
        matchMode == 2 -> "🔒 Private lobby"
        matchMode == 3 -> "🤖 Bot match"
        matchMode == 7 -> "🧪 Hero Labs"
        matchMode == MATCH_MODE_PLACEMENT -> "📍 Placement"
        else -> null
    }

    /** Matches that can move a player's ranked badge. */
    fun affectsRank(gameMode: Int, matchMode: Int) =
        gameMode != GAME_MODE_STREET_BRAWL && (matchMode == MATCH_MODE_RANKED || matchMode == MATCH_MODE_PLACEMENT)
}
