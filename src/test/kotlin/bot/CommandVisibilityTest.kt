package bot

import dev.kord.common.entity.ApplicationCommandOptionType
import dev.kord.rest.builder.interaction.GlobalMultiApplicationCommandBuilder
import kotlin.test.*

class CommandVisibilityTest {
    private val commands = GlobalMultiApplicationCommandBuilder().apply { botCommands() }.build()
    private fun options(name: String) = commands.single { it.name == name }.options.value.orEmpty()

    @Test fun `every always private command offers an optional public toggle`() {
        for (name in listOf("weekly", "steambot", "trends", "party")) {
            val toggle = options(name).single { it.name == "public" }
            assertEquals(ApplicationCommandOptionType.Boolean, toggle.type, name)
            assertFalse(toggle.required.orElse(false), name)
        }
    }

    @Test fun `required options precede optional ones as Discord demands`() {
        for (command in commands) {
            val required = command.options.value.orEmpty().map { it.required.orElse(false) }
            assertEquals(required.sortedDescending(), required, command.name)
        }
    }

    @Test fun `weekly only shares previews and never subscription changes`() {
        assertTrue(weeklyResponseIsPublic("preview", requested = true))
        assertTrue(weeklyResponseIsPublic("PREVIEW", requested = true))
        assertFalse(weeklyResponseIsPublic("preview", requested = false))
        assertFalse(weeklyResponseIsPublic("enable", requested = true))
        assertFalse(weeklyResponseIsPublic("disable", requested = true))
        assertFalse(weeklyResponseIsPublic(null, requested = true))
    }
}
