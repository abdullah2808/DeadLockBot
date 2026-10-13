package bot

import api.gc.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SteamOnboardingTest {
    private fun gc(status: SteamFriendship = SteamFriendship.NONE, auto: Boolean = true,
                   verify: suspend () -> MatchHistorySnapshot = { MatchHistorySnapshot(emptyList(), emptyList()) }) = object : MatchHistoryProvider {
        override suspend fun recentMatchIds(accountId: String) = emptyList<Long>()
        override suspend fun recentHistory(accountId: String) = verify()
        override fun steamBotIdentity() = SteamBotIdentity(76561198092621951)
        override fun friendship(accountId: String) = status
        override fun autoAcceptFriendsEnabled() = auto
    }
    @Test fun `onboarding exposes profile friend code and automatic signup policy`() {
        val notice = steamOnboarding(gc(), "1")
        assertContains(notice, "132356223")
        assertContains(notice, "https://steamcommunity.com/profiles/76561198092621951/")
        assertContains(notice, "15 minutes")
        assertContains(notice, "Add Friend")
    }
    @Test fun `friend status disabled acceptance and unavailable Steam are accurate`() {
        assertContains(steamOnboarding(gc(SteamFriendship.FRIEND), "1"), "You're friends")
        assertContains(steamOnboarding(gc(auto = false), "1"), "owner must accept")
        assertContains(steamOnboarding(null), "not configured")
    }
    @Test fun `successful GC verification including empty history skips public API`() = runTest {
        assertEquals(emptyList(), verifySignupAccount("1", gc()) { error("API must not run") })
    }
    @Test fun `permission failure allows API fallback and cancellation propagates`() = runTest {
        assertEquals(emptyList(), verifySignupAccount("1", gc(verify = { throw GcHistoryPermissionException("1") })) { emptyList() })
        assertFailsWith<CancellationException> {
            verifySignupAccount("1", gc(verify = { throw CancellationException("cancelled") })) { error("No fallback") }
        }
    }
}
