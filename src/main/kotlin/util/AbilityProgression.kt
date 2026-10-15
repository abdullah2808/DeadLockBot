package util

import models.MatchItem

/** Consume chronological records for one player; upgrade IDs are identities, not AP amounts. */
internal class AbilityProgression {
    private data class State(var tier: Int? = null, val upgrades: MutableMap<Long, Int> = mutableMapOf())
    private val abilities = mutableMapOf<Long, State>()

    fun tier(item: MatchItem): Int? {
        val state = abilities.getOrPut(item.itemId) { State() }
        val upgrade = item.upgradeId
        if (upgrade == 0L) {
            state.tier = 0
            state.upgrades.clear()
            state.upgrades[0] = 0
            return 0
        }
        if (upgrade == null) {
            state.tier = null
            state.upgrades.clear()
            return null
        }
        state.upgrades[upgrade]?.let { return it }
        val previous = state.tier
        if (previous == null || previous !in 0..2) {
            state.tier = null
            return null
        }
        return (previous + 1).also { state.tier = it; state.upgrades[upgrade] = it }
    }

    companion object {
        fun cost(tier: Int): Int = when (tier) { 1 -> 1; 2 -> 2; 3 -> 5; else -> 0 }
    }
}
