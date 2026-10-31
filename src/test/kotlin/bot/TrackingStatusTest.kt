package bot

import api.gc.GcTrackingDiagnostics
import kotlin.test.*

class TrackingStatusTest {
    @Test fun `unknown sync is not reported healthy and all timer sources remain distinct`() {
        val text = trackingStatusText(TrackingRuntime.Sync(), GcTrackingDiagnostics(false, 60000, 3600000, 0), true, null, 2, 3, 4 to 5000L)
        assertContains(text, "No successful sync")
        assertContains(text, "Shared Steam cooldown: in about 60s")
        assertContains(text, "Fresh metadata query: in about 3600s")
        assertContains(text, "Metadata retries: 4 · next check in about 5s")
        assertContains(text, "waiting to send: 2")
    }
    @Test fun `failure preserves last success and prune forgets untracked accounts`() {
        TrackingRuntime.synced("1", 123000)
        TrackingRuntime.failed("1", "Access denied")
        assertEquals(123000, TrackingRuntime.get("1").at)
        assertEquals("Access denied", TrackingRuntime.get("1").issue)
        TrackingRuntime.pending(listOf(FinishedMatch("1", 2), FinishedMatch("2", 3)))
        TrackingRuntime.retain(setOf("2"))
        assertNull(TrackingRuntime.get("1").at)
        assertEquals(0, TrackingRuntime.pendingCount("1"))
    }
    @Test fun `metadata queue snapshot does not fetch reports`() = kotlinx.coroutines.test.runTest {
        var calls = 0
        val service = api.MatchMetadataService(null, { error("No download") }, { calls++; null })
        assertEquals(0 to null, service.pendingStatus("1"))
        assertEquals(0, calls)
        service.lookup("1", 123)
        assertEquals(1, service.pendingStatus("1")!!.first)
        assertEquals(0, service.pendingStatus("2")!!.first)
        assertEquals(1, calls)
    }
    @Test fun `delivery problem names the channel, the cause and the way out`() {
        val text = trackingStatusText(TrackingRuntime.Sync(), null, false, null, 1, 0, 0 to null, "100", ChannelProblem.NO_ACCESS)
        assertContains(text, "Notification channel: <#100>")
        assertContains(text, "permission to post in <#100>")
        assertContains(text, "/unsubscribe")
    }
    @Test fun `healthy channel reports no delivery problem`() {
        val text = trackingStatusText(TrackingRuntime.Sync(), null, false, null, 0, 0, 0 to null, "100")
        assertContains(text, "Notification channel: <#100>")
        assertFalse("delivery problem" in text)
    }
    @Test fun `fallback and busy queue are explained`() {
        val text = trackingStatusText(TrackingRuntime.Sync(), null, false, "123", 0, 0, null)
        assertContains(text, "partial coverage")
        assertContains(text, "busy or unavailable")
        assertContains(text, "Last delivered match: 123")
    }
}
