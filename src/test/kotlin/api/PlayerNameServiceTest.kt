package api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.*

class PlayerNameServiceTest {
    @Test fun `cached GC provider preserves the friend-name lookup used by the command`() = runTest {
        val provider = object : api.gc.MatchHistoryProvider {
            override suspend fun recentMatchIds(accountId: String) = emptyList<Long>()
            override fun steamPlayerName(accountId: String) = if (accountId == "68119056") "bay" else null
        }
        val gc = api.gc.CachedMatchHistoryProvider(provider)
        val names = PlayerNameService(steamName = gc::steamPlayerName, profileName = { error("No profile request needed") })
        assertEquals("bay", names.nameFor("68119056"))
    }
    @Test fun `Steam session name wins and identifies the requested account`() = runTest {
        val names = PlayerNameService(steamName = { account ->
            assertEquals("68119056", account)
            "bay"
        }, profileName = { error("No HTTP lookup needed") })
        assertEquals("bay", names.nameFor("68119056"))
        assertEquals("bay", names.nameFor("76561198028384784"))
    }
    @Test fun `names for other accounts never bleed into the requested player's title`() = runTest {
        val names = PlayerNameService(profileName = { if (it == "107757107") "Arrow" else "bay" })
        assertEquals("Arrow", names.nameFor("107757107"))
        assertEquals("bay", names.nameFor("68119056"))
        assertEquals("Arrow", names.nameFor("107757107"))
    }
    @Test fun `concurrent repeated profile lookups are coalesced and cached`() = runTest {
        var calls = 0
        val names = PlayerNameService(profileName = { calls++; delay(100); "bay" })
        val first = async { names.nameFor("68119056") }
        val second = async { names.nameFor("68119056") }
        assertEquals("bay", first.await())
        assertEquals("bay", second.await())
        assertEquals(1, calls)
    }
    @Test fun `profile name expires and updated Steam session names override cached profiles immediately`() = runTest {
        var now = 0L
        var session: String? = null
        var calls = 0
        val names = PlayerNameService(steamName = { session }, profileName = { "name${++calls}" }, clock = { now })
        assertEquals("name1", names.nameFor("68119056"))
        now = 899999
        assertEquals("name1", names.nameFor("68119056"))
        now = 900000
        assertEquals("name2", names.nameFor("68119056"))
        session = "bay"
        assertEquals("bay", names.nameFor("68119056"))
        assertEquals(2, calls)
    }
    @Test fun `lookup failure uses explicit account ID and retries after a short negative cache`() = runTest {
        var now = 0L
        var calls = 0
        val names = PlayerNameService(profileName = { calls++; throw IOException("Steam unavailable") }, clock = { now })
        assertEquals("Account 68119056", names.nameFor("68119056"))
        now = 59999
        assertEquals("Account 68119056", names.nameFor("68119056"))
        assertEquals(1, calls)
        now = 60000
        names.nameFor("68119056")
        assertEquals(2, calls)
    }
    @Test fun `cancellation propagates without caching a failed name`() = runTest {
        var calls = 0
        val names = PlayerNameService(profileName = {
            if (++calls == 1) throw CancellationException("Cancelled")
            "bay"
        })
        assertFailsWith<CancellationException> { names.nameFor("68119056") }
        assertEquals("bay", names.nameFor("68119056"))
    }
    @Test fun `invalid inputs avoid lookups and display names are bounded single lines`() = runTest {
        val names = PlayerNameService(profileName = { "  b\na\ty  " })
        assertEquals("Unknown player", names.nameFor("invalid"))
        assertEquals("b a y", names.nameFor("68119056"))
        val long = PlayerNameService(profileName = { "a".repeat(1000) })
        assertEquals(80, long.nameFor("68119056").length)
    }
    @Test fun `cache stays bounded and Steam markdown is displayed literally`() = runTest {
        var calls = 0
        val names = PlayerNameService(profileName = { calls++; "player$it" })
        for (account in 1..257) names.nameFor(account.toString())
        names.nameFor("1")
        assertEquals(258, calls)
        assertEquals("\\*\\*bay\\*\\*\\_", "**bay**_".asDiscordTitleText())
        assertEquals("\\[name\\]\\(link\\)", "[name](link)".asDiscordTitleText())
    }
}
