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
    private data class Event(val time: Int, val kind: String, val label: String, val item: Long, val detail: String = "")

    fun render(match: MatchDTO, matchId: Long, assets: ScoreboardAssets, highlightedAccount: Int? = null): ByteArray {
        val info = match.matchInfo
        val players = info.players.sortedWith(compareBy({ it.team }, { it.playerSlot }))
        val icons = assets.items.mapValues { encodeImage(it.value) }
        val portraits = assets.portraits.mapValues { encodeImage(it.value) }
        val abilityMap = assets.catalog.values.associateBy { it.className }
        val events = players.map { playerEvents(it, assets.catalog) }
        val duration = maxOf(info.durationSeconds ?: 0, events.flatten().maxOfOrNull { it.time } ?: 0, 1)
        val html = buildString {
            append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'\">")
            append("<title>Deadlock match $matchId</title><style>$css")
            // Embed each icon once, rather than repeating a data URI for every event.
            icons.forEach { (id, uri) -> append(".icon-$id{background-image:url('$uri');background-size:contain;background-position:center;background-repeat:no-repeat}") }
            append(".asset-icon{display:block;width:100%;height:100%;border-radius:4px}</style></head><body>")
            append("<header><div><p class=\"eyebrow\">DEADLOCK / FULL MATCH REPORT</p><h1>Match $matchId</h1></div><p>${info.durationSeconds?.let(::time) ?: "Unavailable"}<br><small>Final match duration</small></p></header>")
            append("<p class=\"intro\">Hover or focus a hero to see abilities. Hover, focus or tap an item for details. Select a player to inspect their build.</p>")
            append("<div class=\"table-wrap\"><table><thead><tr><th>Player / hero</th><th>K / D / A</th><th>Souls</th><th>Player damage</th><th>Objective damage</th><th>Healing</th><th>Final items</th></tr></thead><tbody>")
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
                    append("<tr class=\"team team-${player.team}\"><th colspan=\"7\">$teamName · $outcome</th></tr>")
                    previousTeam = player.team
                }
                val hero = assets.heroes[player.heroId]
                val heroName = hero?.name ?: "Hero ${player.heroId}"
                val abilities = hero?.abilities.orEmpty().mapNotNull { abilityMap[it] }
                append("<tr class=\"${if (player.accountId == highlightedAccount) "highlighted" else ""}\"><td><button class=\"hero\" data-player=\"$index\" data-tip aria-label=\"${escape("Inspect $heroName") }\">")
                portraits[player.heroId]?.let { append("<img alt=\"\" src=\"$it\">") }
                append("<span><strong>${escape(heroName)}</strong><small>${escape(playerLabel(player))}</small></span>")
                append("<span class=\"tip\"><strong>${escape(heroName)}</strong>")
                if (abilities.isEmpty()) append("<span>Ability descriptions unavailable.</span>")
                else abilities.forEachIndexed { slot, ability ->
                    append("<span class=\"ability\"><b>${slot + 1}. ${escape(ability.name)}</b><span>${escape(description(ability))}</span></span>")
                }
                append("</span></button></td><td>${player.kills} / ${player.deaths} / ${player.assists}</td><td>${number(player.netWorth)}</td>")
                append("<td>${number(player.finalStats?.playerDamage)}</td><td>${number(player.finalStats?.objectiveDamage)}</td><td>${number(player.finalStats?.playerHealing)}</td><td><div class=\"inventory\">")
                val finalItems = player.finalItems.filter { assets.catalog[it.itemId]?.type == "upgrade" }
                if (player.items == null || assets.catalog.isEmpty()) append("<small>Unavailable</small>")
                else if (finalItems.isEmpty()) append("<small>—</small>")
                else finalItems.forEach { append(itemButton(it.itemId, assets.catalog, icons)) }
                append("</div></td></tr>")
            }
            append("</tbody></table></div><section class=\"build\"><div class=\"build-heading\"><div><p class=\"eyebrow\">BUILD PROGRESSION</p><h2>Every purchase. Every recorded ability event.</h2></div><label>Player<select id=\"player\" aria-label=\"Player\">")
            players.forEachIndexed { index, player ->
                append("<option value=\"$index\">${escape(assets.heroes[player.heroId]?.name ?: "Hero ${player.heroId}")} · ${escape(playerLabel(player))}</option>")
            }
            append("</select></label></div><div class=\"controls\"><label>Show events through <output id=\"clock\">${time(duration)}</output><input id=\"time\" aria-label=\"Timeline cutoff\" type=\"range\" min=\"0\" max=\"$duration\" value=\"$duration\"></label><label>Events<select id=\"kind\" aria-label=\"Events\"><option value=\"all\">All events</option><option value=\"item\">Item purchases &amp; sales</option><option value=\"ability\">Ability progression</option></select></label></div>")
            players.forEachIndexed { index, player ->
                append("<article class=\"player-build\" data-build=\"$index\" ${if (index == 0) "" else "hidden"}><h3>${escape(assets.heroes[player.heroId]?.name ?: "Hero ${player.heroId}")} · ${escape(playerLabel(player))}</h3><ol class=\"timeline\">")
                if (player.items == null) append("<li class=\"empty\">This cached match has no build events. Older bot versions did not retain them.</li>")
                else if (events[index].isEmpty()) append("<li class=\"empty\">No build events recorded.</li>")
                events[index].forEachIndexed { step, event ->
                    append("<li data-time=\"${event.time}\" data-kind=\"${event.kind}\"><time>${time(event.time)}</time><span class=\"step\">${step + 1}</span>${itemButton(event.item, assets.catalog, icons)}<div><strong>${escape(event.label)}</strong><small>${escape(event.detail)}</small></div></li>")
                }
                append("</ol><p class=\"filtered-empty\" hidden>No events match this time and filter.</p></article>")
            }
            append("</section><footer>Damage and healing use the final recorded snapshot. Events at the same time retain metadata order. Ability events show recorded order, not inferred tier numbers. Descriptions reflect the current asset catalog and may differ from the match's patch. Missing descriptions are labelled unavailable.</footer>")
            append("<div id=\"tooltip\" role=\"tooltip\" hidden></div><script>$script</script></body></html>")
        }
        return html.toByteArray(Charsets.UTF_8)
    }

    private fun playerEvents(player: Players, catalog: Map<Long, ItemInfo>): List<Event> {
        val events = mutableListOf<Event>()
        player.items.orEmpty().forEach { item ->
            val definition = catalog[item.itemId]
            val name = definition?.name ?: "Unknown asset ${item.itemId}"
            val kind = when (definition?.type) { "upgrade" -> "item"; "ability" -> "ability"; else -> "unknown" }
            val label = when (kind) { "item" -> "Bought $name"; "ability" -> "Ability point · $name"; else -> "Recorded event · $name" }
            val detail = if (kind == "ability") {
                item.upgradeId?.let { "Recorded ability upgrade · exact tier not inferred" } ?: "Upgrade details unavailable in this cached match"
            } else ""
            events += Event(item.gameTimeSeconds, kind, label, item.itemId, detail)
            item.soldTimeSeconds?.takeIf { it > 0 }?.let { events += Event(it, kind, "Sold $name", item.itemId) }
        }
        return events.sortedBy { it.time }
    }

    private fun itemButton(id: Long, catalog: Map<Long, ItemInfo>, icons: Map<Long, String>): String {
        val item = catalog[id]
        val label = item?.name ?: "Unknown asset $id"
        return buildString {
            append("<button class=\"item\" data-tip aria-label=\"${escape(label)}\">")
            if (id in icons) append("<span class=\"asset-icon icon-$id\" aria-hidden=\"true\"></span>")
            else append("<span class=\"placeholder\">?</span>")
            append("<span class=\"tip\"><strong>${escape(label)}</strong><span>${escape(item?.let { description(it) } ?: "Description unavailable.")}</span>")
            item?.description?.let { desc ->
                (1..3).forEach { tier -> scalar(desc["t${tier}_desc"])?.let { append("<span><b>Tier $tier:</b> ${escape(plainText(it))}</span>") } }
            }
            append("</span></button>")
        }
    }

    private fun description(item: ItemInfo): String {
        val text = listOf("desc", "active", "passive", "quip").mapNotNull { scalar(item.description?.get(it)) }
            .map(::plainText).filter { it.isNotBlank() }.distinct()
        val properties = item.properties.orEmpty().values.mapNotNull { value ->
            val property = value as? JsonObject ?: return@mapNotNull null
            val label = scalar(property["label"]) ?: return@mapNotNull null
            val amount = scalar(property["value"])?.takeIf { !it.matches(Regex("^(?:0(?:\\.0+)?|-1(?:\\.0+)?)[a-z%]*$")) } ?: return@mapNotNull null
            val unit = scalar(property["postfix"]).orEmpty()
            "$label: $amount${if (unit.isNotEmpty() && !amount.endsWith(unit)) unit else ""}"
        }.take(12)
        return (text + properties).joinToString("\n").take(4000).ifBlank { "Description unavailable in the current asset catalog." }
    }

    private fun scalar(value: kotlinx.serialization.json.JsonElement?): String? = (value as? JsonPrimitive)?.contentOrNull
    private fun plainText(value: String): String = value.replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    private fun playerLabel(player: Players) = player.name?.takeIf { it.isNotBlank() } ?: "Account ${player.accountId.toUInt()}"
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
    private fun number(value: Int?) = value?.let { String.format(java.util.Locale.US, "%,d", it) } ?: "—"
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
        :root{color-scheme:dark;--bg:#101620;--panel:#1b2432;--ink:#edf1f8;--muted:#9eafc3;--amber:#e9b862;--blue:#7bb7f0}*{box-sizing:border-box}body{margin:0;padding:32px;background:var(--bg);color:var(--ink);font:15px/1.5 system-ui,sans-serif}header,.build-heading{display:flex;align-items:center;justify-content:space-between;gap:24px}header h1{margin:0;font-size:36px}header>p{text-align:right;font-size:24px}.eyebrow{letter-spacing:2px;color:var(--amber);font-size:12px;font-weight:700;margin:0}small,.intro,footer{color:var(--muted)}small{display:block;font-size:12px}.intro{margin:16px 0 24px}.table-wrap{overflow-x:auto;border:1px solid #344153;border-radius:12px}table{width:100%;border-collapse:collapse;min-width:1050px}th,td{padding:13px;text-align:left;border-bottom:1px solid #303c4e}thead{font-size:12px;color:var(--muted)}.team th{color:var(--amber);background:#2c2924;letter-spacing:1px}.team-1 th{color:var(--blue);background:#202d3e}.highlighted{outline:1px solid var(--amber);outline-offset:-2px}tbody tr:not(.team):hover{background:#1b2432}button{font:inherit;cursor:pointer;color:inherit}.hero{display:flex;align-items:center;gap:12px;border:0;background:transparent;padding:0;text-align:left;min-width:190px}.hero img{width:48px;height:48px;object-fit:contain}.inventory{display:flex;flex-wrap:wrap;gap:5px;max-width:340px;min-width:200px}.item{border:1px solid #445167;background:#263245;padding:0;border-radius:5px;width:34px;height:34px;flex-shrink:0}.item img{width:100%;height:100%;border-radius:4px}.placeholder{display:block;font-size:18px}button:hover,button:focus-visible{outline:2px solid var(--amber);outline-offset:3px}.tip{display:none}#tooltip{position:fixed;z-index:10;background:#253247;border:1px solid #63778f;border-radius:10px;padding:16px;width:min(360px,90vw);max-height:65vh;overflow:auto;box-shadow:0 12px 40px #0008;pointer-events:auto;white-space:pre-line}#tooltip>strong{display:block;color:var(--amber);font-size:17px;margin-bottom:8px}#tooltip span,.ability{display:block;margin-top:8px}.ability b{color:var(--blue)}.build{margin-top:32px;padding:24px;background:var(--panel);border-radius:12px}.build h2{margin:4px 0;font-size:24px}.controls{display:flex;gap:24px;align-items:center;margin:24px 0}.controls>label:first-child{flex:1}label{font-size:12px;color:var(--muted)}select{display:block;padding:10px;background:#101b2a;color:var(--ink);border:1px solid #53657c;border-radius:6px;max-width:350px;font:inherit;font-size:14px}input[type=range]{display:block;width:100%;accent-color:var(--amber)}output{color:var(--amber);font-weight:700}.timeline{list-style:none;padding:0;margin:0}.timeline li{display:flex;gap:14px;align-items:center;padding:12px 0;border-top:1px solid #344254}.timeline time{min-width:58px;font-variant-numeric:tabular-nums;color:var(--amber)}.step{color:var(--muted);font-size:12px;width:25px}.timeline li[data-kind=ability] time{color:var(--blue)}[hidden]{display:none!important}footer{font-size:12px;max-width:1000px;margin-top:24px}@media(max-width:700px){body{padding:16px}.build-heading,.controls{align-items:stretch;flex-direction:column}.build{padding:16px}select{max-width:100%;width:100%}header h1{font-size:26px}}
    """.trimIndent()

    private val script = """
        const player=document.getElementById('player'),slider=document.getElementById('time'),kind=document.getElementById('kind'),tip=document.getElementById('tooltip');
        function update(){document.getElementById('clock').textContent=Math.floor(slider.value/60)+':'+String(slider.value%60).padStart(2,'0');document.querySelectorAll('[data-build]').forEach(panel=>{panel.hidden=panel.dataset.build!==player.value;let visible=0;panel.querySelectorAll('[data-time]').forEach(event=>{event.hidden=Number(event.dataset.time)>Number(slider.value)||(kind.value!=='all'&&event.dataset.kind!==kind.value);if(!event.hidden)visible++});panel.querySelector('.filtered-empty').hidden=visible>0||!!panel.querySelector('.empty')})}
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
