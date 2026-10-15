package util

import dev.kord.rest.builder.message.create.UserMessageCreateBuilder
import dev.kord.rest.builder.component.ActionRowBuilder
import dev.kord.rest.builder.component.ButtonBuilder.InteractionButtonBuilder
import kotlin.test.*

class MatchReportLinksTest {
    @Test fun `report buttons use a durable match ID and reject malformed or unrelated controls`() {
        assertEquals(116398473L, matchReportId("match-report:116398473"))
        listOf("mx:key:open", "match-report:", "match-report:0", "match-report:-1",
            "match-report:12:other", "match-report:99999999999999999999").forEach { assertNull(matchReportId(it)) }
    }

    @Test fun `empty recaps omit the action row and normal recaps use one row`() {
        val empty = UserMessageCreateBuilder().apply { matchReportButtons(emptyList()) }
        assertTrue(empty.components.isNullOrEmpty())
        val recent = UserMessageCreateBuilder().apply { matchReportButtons(listOf(116398473)) }
        assertEquals(1, recent.components?.size)
        val button = (recent.components!!.single() as ActionRowBuilder).components.single() as InteractionButtonBuilder
        assertEquals("Open full match", button.label)
        assertEquals(116398473L, matchReportId(button.customId))
        val recap = UserMessageCreateBuilder().apply { matchReportButtons((1L..8L).toList()) }
        assertEquals(1, recap.components?.size)
        val buttons = (recap.components!!.single() as ActionRowBuilder).components.map { it as InteractionButtonBuilder }
        assertEquals((1L..5L).toList(), buttons.map { matchReportId(it.customId) })
    }
}
