package util

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import models.ItemInfo
import models.MatchDTO
import models.Players
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/** Downloadable report with embedded assets; no web server or external scripts are required. */
object InteractiveMatchReport {
    internal data class Event(val time: Int, val kind: String, val label: String, val item: Long? = null, val detail: String = "")

    fun render(match: MatchDTO, matchId: Long, assets: ScoreboardAssets, highlightedAccount: Int? = null): ByteArray {
        val info = match.matchInfo
        val players = info.players.sortedWith(compareBy({ it.team }, { it.playerSlot }))
        val icons = assets.items.mapValues { encodeImage(it.value) }
        val portraits = assets.portraits.mapValues { encodeImage(it.value) }
        val abilityMap = assets.catalog.values.associateBy { it.className }
        val events = players.map { playerEvents(it, players, assets) }
        val duration = maxOf(info.durationSeconds ?: 0, events.flatten().maxOfOrNull { it.time } ?: 0, 1)
        val html = buildString {
            append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'\">")
            append("<title>Deadlock match $matchId</title><style>$css")
            // Embed each icon once, rather than repeating a data URI for every event.
            icons.forEach { (id, uri) -> append(".icon-$id{background-image:url('$uri');background-size:contain;background-position:center;background-repeat:no-repeat}") }
            append(".asset-icon{display:block;width:100%;height:100%;border-radius:4px}</style></head><body>")
            append("<header><div class=\"masthead\"><span class=\"sigil\" aria-hidden=\"true\">✦</span><div><p class=\"eyebrow\">THE MATCH DOSSIER</p><h1>Deadlock</h1></div></div><div class=\"match-meta\"><span>MATCH $matchId</span><strong>${info.durationSeconds?.let(::time) ?: "Unavailable"}</strong><small>${players.size} heroes · final match duration</small></div></header>")
            append("<p class=\"intro\">Hover or focus a hero to see abilities. Hover, focus or tap an item for details. Select a hero to inspect their build and combat events.</p>")
            append("<div class=\"category-legend\" aria-label=\"Item categories\"><span class=\"weapon\">Weapon</span><span class=\"vitality\">Vitality</span><span class=\"spirit\">Spirit</span></div>")
            append("<div class=\"table-wrap\"><table><thead><tr><th>Hero</th><th>K / D / A</th><th>Souls</th><th>Player damage</th><th>Objective damage</th><th>Healing</th><th>Final items</th></tr></thead><tbody>")
            var previousTeam: Int? = null
            players.forEachIndexed { index, player ->
                if (player.team != previousTeam) {
                    val teamName = when (player.team) { 0 -> "THE AMBER HAND"; 1 -> "THE SAPPHIRE FLAME"; else -> "OTHER TEAM" }
                    val outcome = when {
                        info.matchOutcome == 2 -> "DRAW"
                        info.matchOutcome == 1 -> "NO CONTEST"
                        info.winningTeam !in 0..1 -> "RESULT UNAVAILABLE"
                        info.winningTeam == player.team -> "VICTORY"
                        else -> "DEFEAT"
                    }
                    val teamPlayers = players.filter { it.team == player.team }
                    append("<tr class=\"team team-${player.team}\"><th colspan=\"7\"><span class=\"team-heading\"><span>$teamName <em>$outcome</em></span><small>${teamPlayers.size} HEROES · ${number(teamPlayers.sumOf { it.netWorth.toLong() })} SOULS</small></span></th></tr>")
                    previousTeam = player.team
                }
                val hero = assets.heroes[player.heroId]
                val heroName = hero?.name ?: "Hero ${player.heroId}"
                val abilities = hero?.abilities.orEmpty().mapNotNull { abilityMap[it] }
                append("<tr class=\"${if (player.accountId == highlightedAccount) "highlighted" else ""}\"><td><button class=\"hero\" data-player=\"$index\" data-tip aria-label=\"${escape("Inspect $heroName") }\">")
                portraits[player.heroId]?.let { append("<img alt=\"\" src=\"$it\">") }
                append("<span><strong>${escape(heroName)}</strong></span>")
                append("<span class=\"tip\"><strong>${escape(heroName)}</strong>")
                if (abilities.isEmpty()) append("<span>Ability descriptions unavailable.</span>")
                else abilities.forEachIndexed { slot, ability ->
                    append("<span class=\"ability\"><b>${slot + 1}. ${escape(ability.name)}</b><span>${escape(description(ability))}</span></span>")
                    (1..3).forEach { tier -> tierDescription(ability, tier)?.let { append("<span class=\"upgrade-effect\"><b>Tier $tier:</b> ${escape(it)}</span>") } }
                }
                append("</span></button></td><td>${player.kills} / ${player.deaths} / ${player.assists}</td><td>${number(player.netWorth)}</td>")
                append("<td>${number(player.finalStats?.playerDamage)}</td><td>${number(player.finalStats?.objectiveDamage)}</td><td>${number(player.finalStats?.playerHealing)}</td><td><div class=\"inventory\">")
                val finalItems = player.finalItems.filter { assets.catalog[it.itemId]?.type == "upgrade" }
                if (player.items == null || assets.catalog.isEmpty()) append("<small>Unavailable</small>")
                else if (finalItems.isEmpty()) append("<small>—</small>")
                else finalItems.forEach { append(itemButton(it.itemId, assets.catalog, icons)) }
                append("</div></td></tr>")
            }
            append("</tbody></table></div><section class=\"build\"><div class=\"build-heading\"><div><p class=\"eyebrow\">THE MATCH LEDGER</p><h2>Trace the path to victory.</h2></div><label>Hero<select id=\"player\" aria-label=\"Hero\">")
            players.forEachIndexed { index, player ->
                append("<option value=\"$index\">${escape(assets.heroes[player.heroId]?.name ?: "Hero ${player.heroId}")}</option>")
            }
            append("</select></label></div><div class=\"controls\"><label>Show events through <output id=\"clock\">${time(duration)}</output><input id=\"time\" aria-label=\"Timeline cutoff\" type=\"range\" min=\"0\" max=\"$duration\" value=\"$duration\"></label><label>Events<select id=\"kind\" aria-label=\"Events\"><option value=\"all\">All events</option><option value=\"item\">Item purchases &amp; sales</option><option value=\"ability\">Ability progression</option><option value=\"combat\">Kills &amp; deaths</option><option value=\"kill\">Kills only</option><option value=\"death\">Deaths only</option></select></label></div>")
            players.forEachIndexed { index, player ->
                append("<article class=\"player-build\" data-build=\"$index\" ${if (index == 0) "" else "hidden"}><h3>${escape(assets.heroes[player.heroId]?.name ?: "Hero ${player.heroId}")}</h3>")
                if (player.items == null) append("<p class=\"data-notice\">Build events unavailable. Older bot versions did not retain them.</p>")
                if (players.any { it.deathDetails == null || it.deathDetails.size < it.deaths }) append("<p class=\"data-notice\">Combat events may be incomplete: this cached match lacks some death records.</p>")
                append("<ol class=\"timeline\">")
                if (events[index].isEmpty()) append("<li class=\"empty\">No timeline events recorded.</li>")
                events[index].forEachIndexed { step, event ->
                    val marker = event.item?.let { itemButton(it, assets.catalog, icons) }
                        ?: "<span class=\"combat-marker ${event.kind}\" aria-hidden=\"true\">${if (event.kind == "kill") "⚔" else "✝"}</span>"
                    append("<li data-time=\"${event.time}\" data-kind=\"${event.kind}\"><time>${time(event.time)}</time><span class=\"step\">${step + 1}</span>$marker<div><strong>${escape(event.label)}</strong><small>${escape(event.detail)}</small></div></li>")
                }
                append("</ol><p class=\"filtered-empty\" hidden>No events match this time and filter.</p></article>")
            }
            append("</section><footer>Damage and healing use the final recorded snapshot. Events at the same time preserve order within each source; ordering between combat and build events is not inferred. Ability events show recorded order, not inferred tier numbers. Descriptions reflect the current asset catalog and may differ from the match's patch. Missing descriptions are labelled unavailable.</footer>")
            append("<div id=\"tooltip\" role=\"tooltip\" hidden></div><script>$script</script></body></html>")
        }
        return html.toByteArray(Charsets.UTF_8)
    }

    internal fun playerEvents(player: Players, players: List<Players>, assets: ScoreboardAssets): List<Event> {
        val events = mutableListOf<Event>()
        val catalog = assets.catalog
        // Only unambiguous slots identify attackers; older cached rosters can default every slot to zero.
        val slots = players.groupBy { it.playerSlot }.filterValues { it.size == 1 }.mapValues { it.value.single() }
        fun heroName(participant: Players) = assets.heroes[participant.heroId]?.name ?: "Hero ${participant.heroId}"
        players.forEach { victim -> victim.deathDetails.orEmpty().forEach deaths@ { death ->
            val timestamp = death.gameTimeSeconds?.takeIf { it >= 0 } ?: return@deaths
            val killer = slots[death.killerPlayerSlot]
            if (victim.accountId == player.accountId) {
                val label = when {
                    killer == null -> "Died · attacker unavailable"
                    killer.accountId == victim.accountId -> "Died · self-inflicted"
                    else -> "Killed by ${heroName(killer)}"
                }
                events += Event(timestamp, "death", label,
                    detail = death.deathDurationSeconds?.takeIf { it > 0 }?.let { "Recorded death duration: ${it}s" }.orEmpty())
            }
            if (killer?.accountId == player.accountId && victim.accountId != player.accountId) {
                events += Event(timestamp, "kill", "Killed ${heroName(victim)}")
            }
        } }
        val progression = AbilityProgression()
        player.items.orEmpty().sortedBy { it.gameTimeSeconds }.forEach { item ->
            val definition = catalog[item.itemId]
            val name = definition?.name ?: "Unknown asset ${item.itemId}"
            val kind = when (definition?.type) { "upgrade" -> "item"; "ability" -> "ability"; else -> "unknown" }
            val tier = if (kind == "ability") progression.tier(item) else null
            val label = when {
                kind == "item" -> "Bought $name"
                kind == "ability" && tier == 0 -> "Unlocked $name · Tier 0"
                kind == "ability" && tier != null -> "Upgraded $name · Tier $tier"
                kind == "ability" -> "Ability upgrade · $name"
                else -> "Recorded event · $name"
            }
            val detail = when {
                kind != "ability" || tier == 0 -> ""
                tier != null -> "${AbilityProgression.cost(tier)} AP · tier inferred from recorded progression"
                else -> "Tier unavailable: incomplete recorded progression"
            }
            events += Event(item.gameTimeSeconds, kind, label, item.itemId, detail)
            item.soldTimeSeconds?.takeIf { it > 0 }?.let { events += Event(it, kind, "Sold $name", item.itemId) }
        }
        return events.sortedBy { it.time }
    }

    private fun itemButton(id: Long, catalog: Map<Long, ItemInfo>, icons: Map<Long, String>): String {
        val item = catalog[id]
        val label = item?.name ?: "Unknown asset $id"
        return buildString {
            val category = item?.let(::category)
            append("<button class=\"item ${category.orEmpty()}\" data-tip aria-label=\"${escape(label)}\">")
            if (id in icons) append("<span class=\"asset-icon icon-$id\" aria-hidden=\"true\"></span>")
            else append("<span class=\"placeholder\">?</span>")
            append("<span class=\"tip\"><strong>${escape(label)}</strong>")
            if (item?.type == "upgrade") {
                val summary = listOfNotNull(category?.replaceFirstChar { it.uppercaseChar() }, item.itemTier?.let { "Tier $it" },
                    item.cost?.let { "${number(it)} souls" }, item.isActiveItem?.let { if (it) "Active" else "Passive" }).joinToString(" · ")
                append("<span class=\"item-meta ${category.orEmpty()}\">${escape(summary)}</span>")
                val components = item.componentItems.orEmpty().mapNotNull { component -> catalog.values.firstOrNull { it.className == component }?.name }
                if (components.isNotEmpty()) append("<span>Upgrades from: ${escape(components.joinToString(", "))}</span>")
            }
            append("<span>${escape(item?.let { description(it) } ?: "Description unavailable.")}</span>")
            if (item != null) (1..3).forEach { tier -> tierDescription(item, tier)?.let { append("<span><b>Tier $tier:</b> ${escape(it)}</span>") } }
            append("</span></button>")
        }
    }

    internal fun description(item: ItemInfo): String {
        val text = listOf("desc", "active", "passive", "quip").mapNotNull { scalar(item.description?.get(it)) }
            .map(::plainText).filter { it.isNotBlank() }.distinct()
        val properties = item.properties.orEmpty().values.mapNotNull { value ->
            val property = value as? JsonObject ?: return@mapNotNull null
            val label = scalar(property["label"]) ?: return@mapNotNull null
            val amount = scalar(property["value"])?.takeIf { !it.matches(Regex("^(?:0(?:\\.0+)?|-1(?:\\.0+)?)[a-z%]*$")) } ?: return@mapNotNull null
            if (amount == scalar(property["disable_value"])) return@mapNotNull null
            val unit = scalar(property["postfix"]).orEmpty()
            val scaling = property["scale_function"] as? JsonObject
            val scaleStat = when (scalar(scaling?.get("specific_stat_scale_type"))) { "ETechPower" -> "Spirit Power"; "EWeaponPower" -> "Weapon Damage"; else -> null }
            val factor = scalar(scaling?.get("stat_scale"))?.toDoubleOrNull()?.takeIf { it != 0.0 }
            val scaleText = if (scaleStat != null && factor != null) " + $factor × $scaleStat" else ""
            "$label: $amount${if (unit.isNotEmpty() && !amount.endsWith(unit)) unit else ""}$scaleText"
        }.take(12)
        return (text + properties).joinToString("\n").take(4000).ifBlank { "Description unavailable in the current asset catalog." }
    }

    // Adapted from Soul Ledger's category and live-property formatting.
    private fun category(item: ItemInfo): String? = when {
        item.itemSlotType?.contains("weapon", ignoreCase = true) == true -> "weapon"
        item.itemSlotType?.let { it.contains("armor", true) || it.contains("vitality", true) } == true -> "vitality"
        item.itemSlotType?.let { it.contains("tech", true) || it.contains("spirit", true) } == true -> "spirit"
        else -> null
    }

    internal fun tierDescription(item: ItemInfo, tier: Int): String? {
        scalar(item.description?.get("t${tier}_desc"))?.takeIf { it.isNotBlank() }?.let { return plainText(it) }
        val upgrade = item.upgrades?.getOrNull(tier - 1) as? JsonObject ?: return null
        val properties = upgrade["property_upgrades"] as? kotlinx.serialization.json.JsonArray ?: return null
        return properties.mapNotNull { entry ->
            val property = entry as? JsonObject ?: return@mapNotNull null
            val name = scalar(property["name"]) ?: return@mapNotNull null
            val value = scalar(property["bonus"])?.toDoubleOrNull() ?: return@mapNotNull null
            val sign = if (value > 0) "+" else ""
            val label = name.replace(Regex("([a-z])([A-Z])"), "$1 $2").removePrefix("Ability ")
            val scaleStat = when (scalar(property["scale_stat_filter"])) { "ETechPower" -> " per Spirit Power"; "EWeaponPower" -> " per Weapon Damage"; else -> "" }
            val unit = if (scaleStat.isNotEmpty()) "" else when {
                name.contains("Cooldown") || name.contains("Duration") -> "s"
                name.contains("Percent") || name.contains("Pct") -> "%"
                name.contains("Range") || name.contains("Radius") -> "m"
                else -> ""
            }
            "$label: $sign$value$unit$scaleStat"
        }.joinToString("; ").takeIf { it.isNotBlank() }
    }

    private fun scalar(value: kotlinx.serialization.json.JsonElement?): String? = (value as? JsonPrimitive)?.contentOrNull
    private fun plainText(value: String): String = value.replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
    private fun number(value: Int?) = value?.let { String.format(java.util.Locale.US, "%,d", it) } ?: "—"
    private fun number(value: Long) = String.format(java.util.Locale.US, "%,d", value)
    private fun time(value: Int) = "${value.coerceAtLeast(0) / 60}:${(value.coerceAtLeast(0) % 60).toString().padStart(2, '0')}"
    private fun encodeImage(source: BufferedImage): String {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().let { g ->
            try { g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); g.drawImage(source, 0, 0, 64, 64, null) }
            finally { g.dispose() }
        }
        return ByteArrayOutputStream().use { output -> ImageIO.write(image, "png", output); "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray()) }
    }

    private val css = """
        :root{color-scheme:dark;--bg:#10130f;--panel:#1a1e18;--ink:#ede6d1;--muted:#a19f8c;--amber:#dab66b;--blue:#81a9c5;--line:#494735}
        *{box-sizing:border-box}body{margin:0 auto;max-width:1680px;padding:36px 40px;background:radial-gradient(ellipse at 5% 0%,#3a332033,transparent 45%),radial-gradient(ellipse at 95% 15%,#263c4333,transparent 45%),repeating-linear-gradient(0deg,transparent,transparent 3px,#ffffff02 4px),var(--bg);color:var(--ink);font:15px/1.5 'Trebuchet MS',system-ui,sans-serif}
        header,.build-heading{display:flex;align-items:center;justify-content:space-between;gap:24px}header{padding:12px 0 24px;border-bottom:3px double var(--line)}.masthead{display:flex;gap:24px;align-items:center}.sigil{display:grid;place-items:center;border:1px solid var(--amber);outline:1px solid #dab66b55;outline-offset:7px;border-radius:50%;width:64px;height:64px;font:52px/1 Georgia,serif;color:var(--amber)}header h1{margin:0;font:700 64px/.95 Georgia,'Times New Roman',serif;letter-spacing:3px;text-transform:uppercase}.eyebrow{letter-spacing:3px;color:var(--amber);font-size:11px;font-weight:700;margin:0 0 10px}.match-meta{text-align:right}.match-meta>span{display:block;font-size:12px;letter-spacing:2px;color:var(--amber)}.match-meta>strong{font:30px Georgia,serif;display:block;margin:3px 0}
        small,.intro,footer{color:var(--muted)}small{display:block;font-size:12px}.intro{margin:18px 0 26px;font-size:13px}.table-wrap{overflow-x:auto;border:1px solid var(--line);box-shadow:0 12px 40px #0004}table{width:100%;border-collapse:collapse;min-width:1110px}th,td{padding:14px 16px;text-align:left;border-bottom:1px solid #3a3d30}thead{font-size:10px;color:var(--muted);text-transform:uppercase;letter-spacing:1px;background:#1b1e17}td:not(:first-child){font-variant-numeric:tabular-nums}td:nth-child(3){color:var(--amber)}.team th{padding:16px;background:linear-gradient(90deg,#5a4421cc,#312b1d80);border-top:2px solid var(--amber);border-bottom:1px solid #856638;color:var(--amber)}.team-1 th{background:linear-gradient(90deg,#263d50cc,#1b2b3580);border-color:#628ca6;color:var(--blue)}.team-heading{display:flex;justify-content:space-between;align-items:center;gap:16px;font:700 19px Georgia,serif;letter-spacing:1px}.team-heading em{font-weight:400;font-size:17px;letter-spacing:0;text-transform:capitalize;margin-left:18px;color:var(--ink)}.team-heading small{font:11px 'Trebuchet MS',sans-serif;letter-spacing:1px;color:inherit;white-space:nowrap}
        tbody tr:not(.team){background:#1b1e1780}tbody tr:not(.team):nth-child(odd){background:#24261d80}tbody tr:not(.team):hover{background:#35382999}.highlighted{outline:1px solid var(--amber);outline-offset:-2px}button{font:inherit;cursor:pointer;color:inherit}.hero{display:flex;align-items:center;gap:16px;border:0;background:transparent;padding:0;text-align:left;min-width:190px}.hero strong{font:700 18px Georgia,serif}.hero img{width:52px;height:52px;object-fit:contain;filter:drop-shadow(0 3px 3px #0008)}.inventory{display:flex;flex-wrap:wrap;gap:5px;max-width:390px;min-width:220px}.item{border:1px solid #77715a;background:#303326;padding:0;border-radius:2px;width:34px;height:34px;flex-shrink:0;box-shadow:0 2px 3px #0005}.item img{width:100%;height:100%;border-radius:1px}.placeholder{display:block;font-size:18px}button:hover,button:focus-visible{outline:2px solid var(--amber);outline-offset:3px}.tip{display:none}
        #tooltip{position:fixed;z-index:10;background:linear-gradient(135deg,#292c20,#181c16);border:1px solid var(--amber);border-top:3px double var(--amber);padding:18px;width:min(380px,90vw);max-height:65vh;overflow:auto;box-shadow:0 16px 48px #000b;pointer-events:auto;white-space:pre-line}#tooltip>strong{display:block;color:var(--amber);font:700 21px Georgia,serif;margin-bottom:10px}#tooltip span,.ability{display:block;margin-top:8px}.ability b{color:var(--blue)}
        .build{margin-top:36px;padding:28px;background:linear-gradient(125deg,#25291d99,#181e1880);border:1px solid var(--line);border-top:3px double var(--amber);box-shadow:0 12px 40px #0003}.build h2{margin:0;font:26px Georgia,serif}.build h3{font:24px Georgia,serif;color:var(--amber);border-bottom:1px solid var(--line);padding-bottom:12px}.controls{display:flex;gap:24px;align-items:center;margin:28px 0}.controls>label:first-child{flex:1}label{font-size:11px;color:var(--muted);letter-spacing:.7px}select{display:block;margin-top:6px;padding:10px 14px;background:#151a13;color:var(--ink);border:1px solid #696751;max-width:350px;font:14px 'Trebuchet MS',sans-serif}input[type=range]{display:block;width:100%;accent-color:var(--amber);margin-top:12px}output{color:var(--amber);font-weight:700}
        .timeline{list-style:none;padding:0;margin:0}.timeline li{display:flex;gap:16px;align-items:center;position:relative;padding:14px 0;border-bottom:1px solid #3e4233}.timeline time{min-width:58px;font-variant-numeric:tabular-nums;color:var(--amber)}.step{display:grid;place-items:center;color:var(--muted);font-size:11px;width:26px;height:26px;border:1px solid #68634c;border-radius:50%;background:var(--panel)}.timeline li[data-kind=ability] time{color:var(--blue)}.timeline li[data-kind=ability] .step{border-color:#628ca6}[hidden]{display:none!important}footer{font-size:11px;line-height:1.8;max-width:1050px;margin-top:24px;border-top:1px solid var(--line);padding-top:16px}
        .weapon{--category:#d97b3f}.vitality{--category:#5cb56a}.spirit{--category:#9b7fe0}.item.weapon,.item.vitality,.item.spirit{border-bottom:3px solid var(--category)}.item-meta{color:var(--category,var(--amber));font-size:12px}.category-legend{display:flex;gap:16px;margin:-8px 0 18px;font-size:11px;letter-spacing:1px;text-transform:uppercase}.category-legend span{color:var(--category);border-left:2px solid var(--category);padding-left:8px}.upgrade-effect{font-size:12px;color:var(--muted)}
        .combat-marker{display:grid;place-items:center;width:34px;height:34px;flex-shrink:0;border:1px solid currentColor;font-size:22px}.combat-marker.kill,.timeline li[data-kind=kill] time{color:#b5cc88}.combat-marker.death,.timeline li[data-kind=death] time{color:#d78476}.data-notice{color:var(--muted);font-size:12px}
        @media(max-width:700px){body{padding:24px 16px}header{align-items:flex-start}.masthead{gap:15px}.sigil{width:38px;height:38px;font-size:30px;outline-offset:4px}header h1{font-size:32px;letter-spacing:1px}.eyebrow{font-size:9px;letter-spacing:1.5px}.match-meta>span{font-size:9px;letter-spacing:1px}.match-meta>strong{font-size:23px}.match-meta small{font-size:10px;max-width:110px}.build-heading,.controls{align-items:stretch;flex-direction:column}.build{padding:20px 16px}select{max-width:100%;width:100%}.team-heading{font-size:17px}}
    """.trimIndent()
    private val script = """
        const player=document.getElementById('player'),slider=document.getElementById('time'),kind=document.getElementById('kind'),tip=document.getElementById('tooltip');
        function update(){document.getElementById('clock').textContent=Math.floor(slider.value/60)+':'+String(slider.value%60).padStart(2,'0');document.querySelectorAll('[data-build]').forEach(panel=>{panel.hidden=panel.dataset.build!==player.value;let visible=0;panel.querySelectorAll('[data-time]').forEach(event=>{event.hidden=Number(event.dataset.time)>Number(slider.value)||(kind.value==='combat'?!['kill','death'].includes(event.dataset.kind):(kind.value!=='all'&&event.dataset.kind!==kind.value));if(!event.hidden)visible++});panel.querySelector('.filtered-empty').hidden=visible>0||!!panel.querySelector('.empty')})}
        [player,slider,kind].forEach(control=>control.addEventListener('input',update));
        function showTip(button){const content=button.querySelector('.tip');if(!content)return;tip.innerHTML=content.innerHTML;tip.hidden=false;const rect=button.getBoundingClientRect(),above=rect.top-12,below=innerHeight-rect.bottom-12,goBelow=below>=above;tip.style.maxHeight=Math.min(innerHeight*.65,Math.max(80,goBelow?below:above))+'px';tip.style.left=Math.max(8,Math.min(rect.left,innerWidth-tip.offsetWidth-8))+'px';tip.style.top=(goBelow?rect.bottom+8:Math.max(8,rect.top-tip.offsetHeight-8))+'px'}
        document.addEventListener('pointerover',event=>{const button=event.target.closest('[data-tip]');if(button)showTip(button)});
        document.addEventListener('pointerout',event=>{const button=event.target.closest('[data-tip]');if(button&&!button.contains(event.relatedTarget)&&!tip.contains(event.relatedTarget))tip.hidden=true;if(tip.contains(event.target)&&!tip.contains(event.relatedTarget))tip.hidden=true});
        document.addEventListener('focusin',event=>{const button=event.target.closest('[data-tip]');if(button)showTip(button)});
        document.addEventListener('focusout',()=>tip.hidden=true);
        document.addEventListener('click',event=>{const hero=event.target.closest('[data-player]');if(hero){player.value=hero.dataset.player;update();document.querySelector('.build').scrollIntoView({behavior:'smooth',block:'start'})}const button=event.target.closest('[data-tip]');if(button)showTip(button);else tip.hidden=true});
        document.addEventListener('keydown',event=>{if(event.key==='Escape')tip.hidden=true});update();
    """.trimIndent()
}
