package api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SteamAccountResolverTest {
    @Test fun `player name lookup requests the canonical numeric profile and verifies identity`() = runTest {
        resolver {
            assertEquals("https://steamcommunity.com/profiles/76561198042843540/?xml=1", it.url.toString())
            assertNull(it.headers["Authorization"])
            respond(profile)
        }.use { lookup -> assertEquals("Onepan", lookup.playerName("82577812")) }
        resolver { respond(profile) }.use { lookup -> assertNull(lookup.playerName("68119056")) }
    }
    @Test fun `player name lookup rejects malformed missing ambiguous and oversized profiles`() = runTest {
        listOf("<profile>", "<html>Unavailable</html>",
            "<profile><steamID64>76561198042843540</steamID64></profile>",
            profile.replace("</profile>", "<steamID>Other name</steamID></profile>"),
            "a".repeat(65537),
            "<!DOCTYPE profile [<!ENTITY name 'bad'>]><profile><steamID64>76561198042843540</steamID64><steamID>&name;</steamID></profile>")
            .forEach { body -> resolver { respond(body) }.use { assertNull(it.playerName("82577812")) } }
    }
    @Test fun `name errors are optional but caller cancellation propagates`() = runTest {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.TooManyRequests, HttpStatusCode.BadGateway, HttpStatusCode.Found)) {
            resolver { respond("Unavailable", status) }.use { assertNull(it.playerName("82577812")) }
        }
        resolver { throw IOException("Unavailable") }.use { assertNull(it.playerName("82577812")) }
        resolver { throw CancellationException("Cancelled") }.use {
            assertFailsWith<CancellationException> { it.playerName("82577812") }
        }
    }
    @Test fun `name lookup times out quickly and invalid accounts avoid requests`() = runTest {
        resolver { kotlinx.coroutines.awaitCancellation() }.use { assertNull(it.playerName("82577812")) }
        resolver { error("No request expected") }.use { assertNull(it.playerName("4294967296")) }
    }
    private val profile = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <profile>
            <steamID64>76561198042843540</steamID64>
            <steamID><![CDATA[Onepan]]></steamID>
            <privacyState>friendsonly</privacyState>
        </profile>
    """.trimIndent()

    private fun TestScope.resolver(handler: MockRequestHandler) = SteamAccountResolver(HttpClient(MockEngine) {
        engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler(handler)
        }
    })

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
