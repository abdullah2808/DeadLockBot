package api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SteamAccountResolverTest {
    private val profile = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <profile>
            <steamID64>76561198042843540</steamID64>
            <steamID><![CDATA[Onepan]]></steamID>
            <privacyState>friendsonly</privacyState>
        </profile>
    """.trimIndent()

    private fun resolver(handler: MockRequestHandler) = SteamAccountResolver(HttpClient(MockEngine(handler)))

    @Test
    fun `numeric inputs and numeric profile URLs never request Steam`() = runTest {
        resolver { error("No Steam request expected") }.use { lookup ->
            listOf("82577812", "76561198042843540", "https://steamcommunity.com/profiles/76561198042843540/")
                .forEach { assertEquals(AccountResolution.Resolved("82577812"), lookup.resolve(it)) }
        }
    }

    @Test
    fun `resolves the reported vanity URL from Steam XML`() = runTest {
        resolver {
            assertEquals("https", it.url.protocol.name)
            assertEquals("steamcommunity.com", it.url.host)
            assertEquals("/id/luisbrrgo/", it.url.encodedPath)
            assertEquals("1", it.url.parameters["xml"])
            respond(profile)
        }.use { lookup ->
            assertEquals(AccountResolution.Resolved("82577812"), lookup.resolve("https://steamcommunity.com/id/luisbrrgo/"))
        }
    }

    @Test
    fun `strips supplied query fragments and uses the canonical Steam origin`() = runTest {
        resolver {
            assertEquals("https://steamcommunity.com/id/luisbrrgo/?xml=1", it.url.toString())
            respond(profile)
        }.use { lookup ->
            assertEquals(AccountResolution.Resolved("82577812"),
                lookup.resolve(" http://www.steamcommunity.com/id/luisbrrgo?redirect=elsewhere#top "))
        }
    }

    @Test
    fun `numeric vanity name is resolved instead of converted locally`() = runTest {
        resolver {
            assertEquals("/id/76561198000000000/", it.url.encodedPath)
            respond(profile)
        }.use { lookup ->
            assertEquals(AccountResolution.Resolved("82577812"), lookup.resolve("https://steamcommunity.com/id/76561198000000000/"))
        }
    }

    @Test
    fun `invalid hosts and numbers do not trigger network requests`() = runTest {
        resolver { error("No Steam request expected") }.use { lookup ->
            listOf("4294967296", "https://example.com/id/luisbrrgo/", "not-an-id").forEach {
                assertEquals(AccountResolution.Invalid, lookup.resolve(it))
            }
        }
    }

    @Test
    fun `missing Steam profile is invalid`() = runTest {
        resolver { respond("Not found", HttpStatusCode.NotFound) }.use { lookup ->
            assertEquals(AccountResolution.Invalid, lookup.resolve("https://steamcommunity.com/id/missing/"))
        }
    }

    @Test
    fun `rate limits service errors and redirects are temporary lookup failures`() = runTest {
        listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable, HttpStatusCode.Found).forEach { status ->
            resolver { respond("Unavailable", status) }.use { lookup ->
                assertEquals(AccountResolution.Unavailable, lookup.resolve("https://steamcommunity.com/id/luisbrrgo/"))
            }
        }
    }

    @Test
    fun `transport failure is unavailable and cancellation still propagates`() = runTest {
        resolver { throw IOException("Connection timed out") }.use { lookup ->
            assertEquals(AccountResolution.Unavailable, lookup.resolve("https://steamcommunity.com/id/luisbrrgo/"))
        }
        resolver { throw CancellationException("Cancelled") }.use { lookup ->
            assertFailsWith<CancellationException> { lookup.resolve("https://steamcommunity.com/id/luisbrrgo/") }
        }
    }

    @Test
    fun `does not accept malformed missing ambiguous or invalid Steam identities`() = runTest {
        listOf(
            "<html>Steam is unavailable</html>",
            "<profile>",
            "<response><error>The specified profile could not be found.</error></response>",
            "<profile><steamID64>82577812</steamID64></profile>",
            "<profile><steamID64>76561202255233024</steamID64></profile>",
            "<profile><steamID64>76561198042843540</steamID64><steamID64>76561198042843540</steamID64></profile>",
            "<!DOCTYPE profile [<!ENTITY id '76561198042843540'>]><profile><steamID64>&id;</steamID64></profile>",
        ).forEach { xml ->
            resolver { respond(xml) }.use { lookup ->
                assertEquals(AccountResolution.Unavailable, lookup.resolve("https://steamcommunity.com/id/luisbrrgo/"), xml)
            }
        }
    }
}
