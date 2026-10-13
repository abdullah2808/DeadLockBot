package bot

import dev.kord.common.entity.DiscordChannel
import dev.kord.common.entity.optional.OptionalSnowflake
import dev.kord.gateway.DefaultGateway
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerializationException

class DiscordCompatibilityTest {
    private fun parser(): Json {
        val client = HttpClient(MockEngine { error("No network requests expected") })
        try {
            val gateway = DefaultGateway { this.client = client }.acceptNullableChannelApplicationIds()
            val parserField = DefaultGateway::class.java.getDeclaredField("jsonParser")
            parserField.isAccessible = true
            return parserField.get(gateway) as Json
        } finally {
            client.close()
        }
    }

    @Test
    fun `voice channel accepts explicit null application id`() {
        val channel = parser().decodeFromString<DiscordChannel>("""
            {"id":"123","type":2,"guild_id":"456","name":"Voice","application_id":null}
        """.trimIndent())

        assertEquals("123", channel.id.toString())
        assertEquals(OptionalSnowflake.Missing, channel.applicationId)
    }

    @Test
    fun `valid channel application id is preserved`() {
        val channel = parser().decodeFromString<DiscordChannel>("""
            {"id":"123","type":2,"application_id":"789"}
        """.trimIndent())

        assertEquals("789", channel.applicationId.value.toString())
    }

    @Test
    fun `required channel id is not silently defaulted`() {
        assertFailsWith<SerializationException> {
            parser().decodeFromString<DiscordChannel>("""{"id":null,"type":2}""")
        }
    }
}
