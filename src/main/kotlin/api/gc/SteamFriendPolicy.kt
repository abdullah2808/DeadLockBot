package api.gc

enum class SteamFriendship { UNKNOWN, NONE, INCOMING, OUTGOING, FRIEND, BLOCKED }

data class SteamBotIdentity(val steamId64: Long) {
    init { require(steamId64 - 76561197960265728L in 1..4294967295L) }
    val friendCode: Long get() = steamId64 - 76561197960265728L
    val profileUrl: String get() = "https://steamcommunity.com/profiles/$steamId64/"
}

/** Accept incoming requests for tracked accounts or a recent signup attempt, never initiate requests. */
internal class SteamFriendPolicy(
    private val accept: (String) -> Unit,
    private val enabled: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val relations = mutableMapOf<String, SteamFriendship>()
    private var tracked = emptySet<String>()
    private val signups = linkedMapOf<String, Long>()
    private val attempted = mutableMapOf<String, Long>()
    private var ready = false

    @Synchronized fun status(account: String): SteamFriendship =
        relations[account] ?: if (ready) SteamFriendship.NONE else SteamFriendship.UNKNOWN

    @Synchronized fun updateTracked(accounts: Set<String>) {
        tracked = accounts.toSet()
        acceptEligible()
    }

    @Synchronized fun authorizeSignup(account: String) {
        signups.remove(account)
        signups[account] = clock() + 15 * 60_000L
        while (signups.size > 100) signups.remove(signups.keys.first())
        acceptEligible()
    }

    @Synchronized fun updateRelationships(updates: Map<String, SteamFriendship>, incremental: Boolean) {
        if (!incremental) relations.clear()
        relations.putAll(updates)
        ready = true
        attempted.keys.retainAll(relations.filterValues { it == SteamFriendship.INCOMING }.keys)
        acceptEligible()
    }

    @Synchronized fun confirm(account: String, success: Boolean) {
        if (success) { relations[account] = SteamFriendship.FRIEND; attempted.remove(account); signups.remove(account) }
    }

    @Synchronized fun disconnected() {
        ready = false
        relations.clear()
        attempted.clear()
    }

    private fun acceptEligible() {
        signups.entries.removeIf { it.value <= clock() }
        if (!enabled || !ready) return
        for ((account, relation) in relations) {
            if (relation != SteamFriendship.INCOMING || (account !in tracked && account !in signups)) continue
            val last = attempted[account]
            if (last != null && clock() - last < 60_000L) continue
            attempted[account] = clock()
            try { accept(account) } catch (e: Exception) {
                println("Steam friend acceptance failed for $account: ${e.javaClass.simpleName}")
            }
        }
    }
}
