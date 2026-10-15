package bot

import dev.kord.common.entity.ButtonStyle
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.edit
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.SelectMenuInteraction
import dev.kord.core.event.interaction.ComponentInteractionCreateEvent
import dev.kord.core.on
import dev.kord.rest.builder.component.actionRow
import dev.kord.rest.builder.component.option
import dev.kord.rest.builder.message.MessageBuilder
import models.MatchDTO
import util.InteractiveMatchReport
import util.ScoreboardAssets
import java.util.UUID

/** Cache metadata and catalog only, never the rendered image buffers. */
internal object MatchExplorer {
    data class Report(val match: MatchDTO, val matchId: Long, val assets: ScoreboardAssets, val created: Long)
    private val reports = LinkedHashMap<String, Report>()
    private const val TTL = 60 * 60 * 1000L

    @Synchronized fun store(match: MatchDTO, matchId: Long, assets: ScoreboardAssets): String {
        reports.entries.removeIf { System.currentTimeMillis() - it.value.created >= TTL }
        while (reports.size >= 50) reports.remove(reports.keys.first())
        val key = UUID.randomUUID().toString().take(12)
        reports[key] = Report(match, matchId, assets.copy(portraits = emptyMap(), items = emptyMap()), System.currentTimeMillis())
        return key
    }

    @Synchronized fun get(key: String): Report? = reports[key]?.takeIf { System.currentTimeMillis() - it.created < TTL }

    data class View(val player: Int = 0, val section: String = "stats", val filter: String = "all", val page: Int = 0, val item: Long? = null)
    private fun id(key: String, action: String, v: View) = "mx:$key:$action:${v.player}:${v.section}:${v.filter}:${v.page}:${v.item ?: 0}"
    fun parse(parts: List<String>): View = View(parts.getOrNull(3)?.toIntOrNull() ?: 0,
        parts.getOrNull(4) ?: "stats", parts.getOrNull(5) ?: "all", parts.getOrNull(6)?.toIntOrNull() ?: 0,
        parts.getOrNull(7)?.toLongOrNull()?.takeIf { it > 0 })

    fun text(r: Report, v: View): String {
        val players = r.match.matchInfo.players
        val p = players.getOrNull(v.player) ?: return "Player unavailable. Run `/match ${r.matchId}` again."
        fun name(id: Long) = r.assets.catalog[id]?.name ?: "Asset $id"
        val hero = r.assets.heroes[p.heroId]
        val heading = "**Match ${r.matchId} · ${hero?.name ?: "Hero ${p.heroId}"} · ${v.section.replaceFirstChar { it.uppercase() }}**\n"
        val body = when (v.section) {
            "build" -> {
                val items = p.finalItems.filter { r.assets.catalog[it.itemId]?.type != "ability" }
                val detail = v.item?.takeIf { selected -> items.any { it.itemId == selected } }?.let { r.assets.catalog[it] }
                (if (p.items == null) "Inventory unavailable in this cached match." else items.joinToString("\n") { "• ${name(it.itemId)}" }.ifBlank { "No final items recorded." }) +
                    (detail?.let { "\n\n**${it.name}** · ${it.cost ?: "Unknown"} souls\n${InteractiveMatchReport.description(it)}" } ?: "")
            }
            "abilities" -> hero?.abilities.orEmpty().mapNotNull { cls -> r.assets.catalog.values.firstOrNull { it.className == cls } }
                .joinToString("\n\n") { "**${it.name}**\n${(InteractiveMatchReport.description(it).take(230) + (1..3).mapNotNull { tier -> InteractiveMatchReport.tierDescription(it, tier)?.let { effect -> "\nTier $tier: $effect" } }.joinToString("")).take(400)}" }.ifBlank { "Ability descriptions unavailable." }
            "events" -> {
                val events = InteractiveMatchReport.playerEvents(p, players, r.assets).filter { v.filter == "all" || it.kind == v.filter }
                val pages = ((events.size + 7) / 8).coerceAtLeast(1)
                val page = v.page.coerceIn(0, pages - 1)
                "Page ${page + 1}/$pages\n" + events.drop(page * 8).take(8).joinToString("\n") {
                    "`${it.time / 60}:${(it.time % 60).toString().padStart(2, '0')}` ${it.label.take(140)}${if (it.detail.isBlank()) "" else " · ${it.detail.take(60)}"}"
                }.ifBlank { "No recorded events for this filter." } +
                    "\n\n" + (if (p.items == null) "Build event data unavailable. " else "") +
                    (if (players.any { it.deathDetails == null }) "Combat coverage may be incomplete." else "")
            }
            else -> "K/D/A: ${p.kills}/${p.deaths}/${p.assists}\nSouls: ${p.netWorth}\nPlayer damage: ${p.finalStats?.playerDamage ?: "—"}\nObjective damage: ${p.finalStats?.objectiveDamage ?: "—"}\nHealing: ${p.finalStats?.playerHealing ?: "—"}\n\nDescriptions use the current asset catalog; historical values may differ."
        }
        // Catalog descriptions are stripped of HTML by the shared formatter.
        val text = (heading + body).replace("@", "＠")
        return if (text.length > 1950) text.take(1890) + "\n… More detail is available in the HTML report." else text
    }

    fun controls(builder: MessageBuilder, key: String, r: Report, v: View) = with(builder) {
        components = mutableListOf()
        actionRow {
            stringSelect(id(key, "player", v)) {
                placeholder = "Choose a hero / player"
                r.match.matchInfo.players.take(25).forEachIndexed { i, p ->
                    option("${r.assets.heroes[p.heroId]?.name ?: "Hero ${p.heroId}"} · ${if (p.team == 0) "Amber" else "Sapphire"} · slot ${p.playerSlot}".take(100), i.toString()) { default = i == v.player }
                }
            }
        }
        actionRow {
            listOf("stats", "build", "events", "abilities").forEach { section ->
                interactionButton(if (section == v.section) ButtonStyle.Primary else ButtonStyle.Secondary, id(key, "section", v.copy(section = section, page = 0, item = null))) { label = section.replaceFirstChar { it.uppercase() } }
            }
        }
        if (v.section == "events") {
            actionRow { stringSelect(id(key, "filter", v)) {
                placeholder = "Filter events"
                listOf("all" to "All events", "item" to "Purchases & sales", "ability" to "Ability upgrades", "kill" to "Kills", "death" to "Deaths").forEach { (value, title) -> option(title, value) { default = value == v.filter } }
            } }
            val count = InteractiveMatchReport.playerEvents(r.match.matchInfo.players[v.player], r.match.matchInfo.players, r.assets).count { v.filter == "all" || it.kind == v.filter }
            actionRow {
                interactionButton(ButtonStyle.Secondary, id(key, "page", v.copy(page = (v.page - 1).coerceAtLeast(0)))) { label = "Previous"; disabled = v.page <= 0 }
                interactionButton(ButtonStyle.Secondary, id(key, "page", v.copy(page = v.page + 1))) { label = "Next"; disabled = (v.page + 1) * 8 >= count }
            }
        }
        if (v.section == "build") {
            val items = r.match.matchInfo.players[v.player].finalItems.filter { r.assets.catalog[it.itemId]?.type != "ability" }.distinctBy { it.itemId }.take(25)
            if (items.isNotEmpty()) actionRow { stringSelect(id(key, "item", v)) {
                placeholder = "Inspect a final item"
                items.forEach { item -> option((r.assets.catalog[item.itemId]?.name ?: "Asset ${item.itemId}").take(100), item.itemId.toString()) { default = item.itemId == v.item } }
            } }
        }
    }
}

internal fun registerMatchExplorer(kord: Kord) {
    kord.on<ComponentInteractionCreateEvent> {
        val parts = interaction.componentId.split(":")
        if (parts.firstOrNull() != "mx") return@on
        val key = parts.getOrNull(1).orEmpty()
        val report = MatchExplorer.get(key)
        if (report == null) {
            interaction.deferEphemeralResponse().respond { content = "This report has expired or the bot restarted. Run `/match` again to reopen it." }
            return@on
        }
        var view = MatchExplorer.parse(parts)
        val value = (interaction as? SelectMenuInteraction)?.values?.firstOrNull()
        view = when (parts.getOrNull(2)) {
            "player" -> view.copy(player = value?.toIntOrNull() ?: 0, page = 0, item = null)
            "filter" -> view.copy(filter = value ?: "all", page = 0)
            "item" -> view.copy(item = value?.toLongOrNull())
            else -> view
        }
        view = view.copy(player = view.player.coerceIn(0, report.match.matchInfo.players.lastIndex), page = view.page.coerceAtLeast(0))
        if (parts.getOrNull(2) == "open") {
            interaction.deferEphemeralResponse().respond {
                content = MatchExplorer.text(report, view)
                MatchExplorer.controls(this, key, report, view)
            }
        } else {
            interaction.deferEphemeralMessageUpdate().edit {
                content = MatchExplorer.text(report, view)
                MatchExplorer.controls(this, key, report, view)
            }
        }
    }
}
