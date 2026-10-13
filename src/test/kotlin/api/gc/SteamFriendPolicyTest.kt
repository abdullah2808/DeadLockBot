package api.gc

import kotlin.test.*

class SteamFriendPolicyTest {
    @Test fun `only incoming requests from tracked or recent signup accounts are accepted`() {
        val accepted = mutableListOf<String>()
        val policy = SteamFriendPolicy(accepted::add)
        policy.updateTracked(setOf("1", "3", "4"))
        policy.authorizeSignup("2")
        policy.updateRelationships(mapOf("1" to SteamFriendship.INCOMING, "2" to SteamFriendship.INCOMING,
            "3" to SteamFriendship.OUTGOING, "4" to SteamFriendship.BLOCKED, "5" to SteamFriendship.INCOMING), false)
        assertEquals(listOf("1", "2"), accepted)
    }
    @Test fun `request before signup or registration is accepted immediately when authorized`() {
        val accepted = mutableListOf<String>()
        val policy = SteamFriendPolicy(accepted::add)
        policy.updateRelationships(mapOf("1" to SteamFriendship.INCOMING, "2" to SteamFriendship.INCOMING), false)
        assertTrue(accepted.isEmpty())
        policy.authorizeSignup("1")
        policy.updateTracked(setOf("2"))
        assertEquals(listOf("1", "2"), accepted)
    }
    @Test fun `duplicate updates do not resend and success updates friendship`() {
        val accepted = mutableListOf<String>()
        val policy = SteamFriendPolicy(accepted::add)
        policy.updateTracked(setOf("1"))
        repeat(2) { policy.updateRelationships(mapOf("1" to SteamFriendship.INCOMING), it > 0) }
        assertEquals(listOf("1"), accepted)
        policy.confirm("1", true)
        assertEquals(SteamFriendship.FRIEND, policy.status("1"))
        policy.updateTracked(setOf("1"))
        assertEquals(1, accepted.size)
    }
    @Test fun `failed acceptance waits one minute before retry and reconnect refreshes status`() {
        var now = 0L
        var calls = 0
        val policy = SteamFriendPolicy({ calls++ }, clock = { now })
        policy.updateTracked(setOf("1"))
        policy.updateRelationships(mapOf("1" to SteamFriendship.INCOMING), false)
        policy.confirm("1", false)
        policy.updateTracked(setOf("1"))
        assertEquals(1, calls)
        now = 60000
        policy.updateTracked(setOf("1"))
        assertEquals(2, calls)
        policy.disconnected()
        assertEquals(SteamFriendship.UNKNOWN, policy.status("1"))
        policy.updateRelationships(mapOf("1" to SteamFriendship.FRIEND), false)
        assertEquals(SteamFriendship.FRIEND, policy.status("1"))
    }
    @Test fun `expired signup and disabled auto acceptance leave requests pending`() {
        var now = 0L
        val accepted = mutableListOf<String>()
        val policy = SteamFriendPolicy(accepted::add, clock = { now })
        policy.authorizeSignup("1")
        now = 15 * 60000L
        policy.updateRelationships(mapOf("1" to SteamFriendship.INCOMING), false)
        assertTrue(accepted.isEmpty())
        val disabled = SteamFriendPolicy(accepted::add, enabled = false)
        disabled.updateTracked(setOf("1"))
        disabled.updateRelationships(mapOf("1" to SteamFriendship.INCOMING), false)
        assertTrue(accepted.isEmpty())
    }
    @Test fun `Steam bot identity derives its friend code`() {
        val identity = SteamBotIdentity(76561198092621951)
        assertEquals(132356223L, identity.friendCode)
        assertEquals("https://steamcommunity.com/profiles/76561198092621951/", identity.profileUrl)
        assertFailsWith<IllegalArgumentException> { SteamBotIdentity(1) }
    }
}
