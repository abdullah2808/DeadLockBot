package util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountIdTest {
    @Test
    fun `account ids and individual SteamID64 resolve to the same account`() {
        assertEquals("82577812", AccountId.normalize(" 82577812 "))
        assertEquals("82577812", AccountId.normalize("76561198042843540"))
        assertEquals("4294967295", AccountId.normalize("4294967295"))
        assertEquals("4294967295", AccountId.normalize("76561202255233023"))
    }

    @Test
    fun `numeric Steam profiles support normal link variants`() {
        listOf(
            "https://steamcommunity.com/profiles/76561198042843540/",
            "http://www.steamcommunity.com/profiles/76561198042843540?l=english#top",
            "steamcommunity.com/profiles/76561198042843540",
            "<https://steamcommunity.com/profiles/76561198042843540/>",
        ).forEach { assertEquals("82577812", AccountId.normalize(it), it) }
    }

    @Test
    fun `vanity profiles must be looked up even when the alias is numeric`() {
        assertEquals("luisbrrgo", AccountId.vanityName("https://steamcommunity.com/id/luisbrrgo/"))
        assertEquals("76561198042843540", AccountId.vanityName("https://steamcommunity.com/id/76561198042843540/"))
        assertNull(AccountId.normalize("https://steamcommunity.com/id/76561198042843540/"))
        assertNull(AccountId.normalize("https://steamcommunity.com/id/luisbrrgo/"))
    }

    @Test
    fun `rejects inputs outside individual account id range`() {
        listOf("", "0", "-1", "4294967296", "76561197960265728", "76561202255233024",
            "99999999999999999", "not-a-number", "12376561198042843540abc").forEach {
            assertNull(AccountId.normalize(it), it)
        }
    }

    @Test
    fun `only accepts Steam profile hosts and paths`() {
        listOf(
            "https://example.com/profiles/76561198042843540/",
            "https://steamcommunity.com.evil.example/id/luisbrrgo/",
            "https://steamcommunity.com@evil.example/id/luisbrrgo/",
            "https://someone@steamcommunity.com/id/luisbrrgo/",
            "https://steamcommunity.com:8888/id/luisbrrgo/",
            "file://steamcommunity.com/id/luisbrrgo/",
            "https://steamcommunity.com/groups/luisbrrgo/",
            "https://steamcommunity.com/id/luisbrrgo/other",
            "https://steamcommunity.com/id/luis%2Fbrrgo/",
            "https://steamcommunity.com/profiles/82577812/",
        ).forEach {
            assertNull(AccountId.normalize(it), it)
            assertNull(AccountId.vanityName(it), it)
        }
    }
}
