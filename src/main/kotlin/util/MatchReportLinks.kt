package util

import dev.kord.common.entity.ButtonStyle
import dev.kord.rest.builder.component.actionRow
import dev.kord.rest.builder.message.MessageBuilder

private const val MATCH_REPORT_PREFIX = "match-report:"

internal fun matchReportId(componentId: String): Long? = componentId
    .takeIf { it.startsWith(MATCH_REPORT_PREFIX) }
    ?.removePrefix(MATCH_REPORT_PREFIX)?.toLongOrNull()?.takeIf { it > 0 }

/** IDs carry the match number rather than an expiring explorer-cache key. */
internal fun MessageBuilder.matchReportButtons(matchIds: List<Long>) {
    val ids = matchIds.filter { it > 0 }.distinct().take(5)
    if (ids.isEmpty()) return
    actionRow {
        ids.forEach { matchId ->
            interactionButton(ButtonStyle.Primary, "$MATCH_REPORT_PREFIX$matchId") {
                label = if (ids.size == 1) "Open full match" else "Match $matchId"
            }
        }
    }
}
