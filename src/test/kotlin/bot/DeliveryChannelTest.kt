package bot

import dev.kord.common.annotation.KordExperimental
import dev.kord.common.entity.ChannelType
import dev.kord.common.entity.Permission
import dev.kord.common.entity.Permissions
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.createMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.*

@OptIn(KordExperimental::class)
class DeliveryChannelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun kord(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = Kord.restOnly("token") {
        applicationId = Snowflake(1)
        httpClient = HttpClient(MockEngine { handler(it) })
    }

    private fun failing(status: HttpStatusCode, code: Int) = kord {
        respond("""{"code":$code,"message":"nope"}""", status, json)
    }

    private fun postedMessage(channelId: String) = """{"id":"500","channel_id":"$channelId","author":{"id":"1","username":"bot",
        "discriminator":"0","avatar":null},"content":"","timestamp":"2026-01-01T00:00:00Z","edited_timestamp":null,"tts":false,
        "mention_everyone":false,"mentions":[],"mention_roles":[],"attachments":[],"embeds":[],"pinned":false,"type":0}"""

    private suspend fun postFailure(kord: Kord): Throwable =
        assertFails { deliveryChannel(kord, "100").createMessage { content = "hi" } }

    // getChannelOf<TextChannel> returned null for every type here but text, and fetching a voice channel
    // with application_id: null fails to decode. Posting must never depend on that lookup.
    @Test fun `posting goes straight to the stored channel without fetching its type`() = runBlocking {
        val requests = mutableListOf<Pair<HttpMethod, String>>()
        val kord = kord { request ->
            requests += request.method to request.url.encodedPath
            if (request.method == HttpMethod.Get) respond("""{"id":"100","type":2,"application_id":null}""", HttpStatusCode.OK, json)
            else respond(postedMessage("100"), HttpStatusCode.OK, json)
        }
        val message = deliveryChannel(kord, "100").createMessage { content = "hi" }
        assertEquals("100", message.channelId.toString())
        assertEquals(1, requests.size)
        assertEquals(HttpMethod.Post, requests.single().first)
        assertTrue(requests.single().second.endsWith("/channels/100/messages"))
    }

    @Test fun `stored channel ids that are not snowflakes are reported as missing`() {
        val kord = kord { fail("No request expected") }
        for (stored in listOf(null, "null", "channel-1", "")) {
            val error = assertFailsWith<ChannelUnavailableException> { deliveryChannel(kord, stored) }
            assertEquals(ChannelProblem.MISSING, error.problem)
        }
    }

    @Test fun `discord rejections map to the cause the user can act on`() = runBlocking {
        val cases = listOf(
            Triple(HttpStatusCode.Forbidden, 50013, ChannelProblem.NO_ACCESS),
            Triple(HttpStatusCode.Forbidden, 50001, ChannelProblem.NO_ACCESS),
            Triple(HttpStatusCode.NotFound, 10003, ChannelProblem.MISSING),
            Triple(HttpStatusCode.BadRequest, 50083, ChannelProblem.ARCHIVED_THREAD),
            Triple(HttpStatusCode.Forbidden, 160005, ChannelProblem.ARCHIVED_THREAD),
            Triple(HttpStatusCode.BadRequest, 50008, ChannelProblem.NOT_MESSAGEABLE),
            Triple(HttpStatusCode.BadRequest, 40058, ChannelProblem.NOT_MESSAGEABLE),
        )
        for ((status, code, expected) in cases) {
            assertEquals(expected, channelProblem(postFailure(failing(status, code))), "$status / $code")
        }
    }

    @Test fun `failures unrelated to the channel keep the generic handling`() = runBlocking {
        assertNull(channelProblem(postFailure(failing(HttpStatusCode.InternalServerError, 0))))
        // The interaction token expiring is a 404 too, but the channel is fine.
        assertNull(channelProblem(postFailure(failing(HttpStatusCode.NotFound, 10015))))
        assertNull(channelProblem(IllegalStateException("db unavailable")))
    }

    @Test fun `every cause is explained and mentions the channel`() {
        for (problem in ChannelProblem.entries) {
            assertContains(channelProblemMessage("100", problem), "<#100>")
            assertContains(channelProblemMessage(null, problem), "that channel")
        }
        assertContains(channelProblemMessage("100", ChannelProblem.NO_ACCESS), "Send Messages in Threads")
        assertContains(channelProblemMessage("100", ChannelProblem.ARCHIVED_THREAD), "archived or locked")
    }

    @Test fun `last five from another channel says where it posted`() {
        val text = lastFiveConfirmation("100", "200", null)
        assertContains(text, "<#100>")
        assertContains(text, "tracking channel")
    }

    @Test fun `last five from the tracking channel keeps the short confirmation`() {
        assertEquals("✅ Fetched your recent matches.", lastFiveConfirmation("100", "100", null))
        assertEquals("Waiting for match details", lastFiveConfirmation("100", "100", "Waiting for match details"))
    }

    @Test fun `last five notice survives alongside the location`() {
        val text = lastFiveConfirmation("100", "200", "Waiting for match details")
        assertContains(text, "Waiting for match details")
        assertContains(text, "<#100>")
    }

    private val posting = Permissions(Permission.ViewChannel, Permission.EmbedLinks)

    @Test fun `threads need the thread send permission and everything else the normal one`() {
        val normal = posting + Permission.SendMessages
        val threads = posting + Permission.SendMessagesInThreads
        for (type in listOf(ChannelType.GuildText, ChannelType.GuildNews, ChannelType.GuildVoice, ChannelType.GuildStageVoice)) {
            assertTrue(canPost(normal, type), "$type")
            assertFalse(canPost(threads, type), "$type")
        }
        for (type in listOf(ChannelType.PublicGuildThread, ChannelType.PublicNewsThread, ChannelType.PrivateThread)) {
            assertTrue(canPost(threads, type), "$type")
            assertFalse(canPost(normal, type), "$type")
        }
        assertTrue(canPost(threads, null))
        assertFalse(canPost(posting, null))
    }

    @Test fun `viewing and embeds are required too`() {
        assertFalse(canPost(Permissions(Permission.SendMessages, Permission.EmbedLinks), ChannelType.GuildText))
        assertFalse(canPost(Permissions(Permission.SendMessages, Permission.ViewChannel), ChannelType.GuildText))
    }

    private val allowed = posting + Permission.SendMessages

    @Test fun `signup is refused on missing permissions without a request`() = runBlocking {
        val kord = kord { fail("No request expected") }
        assertEquals(ChannelProblem.NO_ACCESS, signupChannelProblem(kord, Snowflake(100), posting, ChannelType.GuildText))
    }

    @Test fun `signup is refused when discord denies the channel despite permissions`() = runBlocking {
        val kord = failing(HttpStatusCode.Forbidden, 50001)
        val threadPermissions = posting + Permission.SendMessagesInThreads
        assertEquals(ChannelProblem.NO_ACCESS, signupChannelProblem(kord, Snowflake(100), threadPermissions, ChannelType.PrivateThread))
    }

    @Test fun `signup outside a guild only needs the channel to be reachable`() = runBlocking {
        val kord = kord { respond("""{"id":"100","type":1}""", HttpStatusCode.OK, json) }
        assertNull(signupChannelProblem(kord, Snowflake(100), null, ChannelType.DM))
    }

    @Test fun `inconclusive signup checks do not block`() = runBlocking {
        assertNull(signupChannelProblem(failing(HttpStatusCode.InternalServerError, 0), Snowflake(100), allowed, ChannelType.GuildText))
        // Kord's REST decoder rejects this payload, which says nothing about whether the bot can post.
        val voice = kord { respond("""{"id":"100","type":2,"guild_id":"7","name":"c","application_id":null}""", HttpStatusCode.OK, json) }
        assertNull(signupChannelProblem(voice, Snowflake(100), allowed, ChannelType.GuildVoice))
    }

    @Test fun `delivery tracking records a channel problem and clears it on success`() = runBlocking {
        val account = "delivery-tracking-test"
        assertFailsWith<ChannelUnavailableException> {
            trackingDelivery(listOf(account)) { throw ChannelUnavailableException(ChannelProblem.NO_ACCESS) }
        }
        assertEquals(ChannelProblem.NO_ACCESS, TrackingRuntime.deliveryProblem(account))
        assertFailsWith<IllegalStateException> { trackingDelivery(listOf(account)) { error("db unavailable") } }
        assertEquals(ChannelProblem.NO_ACCESS, TrackingRuntime.deliveryProblem(account), "unrelated failures keep the last known cause")
        assertEquals("sent", trackingDelivery(listOf(account)) { "sent" })
        assertNull(TrackingRuntime.deliveryProblem(account))
    }
}
