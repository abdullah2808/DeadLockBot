package bot

import api.MetadataLookup.Unavailable
import api.MetadataUnavailableReason
import kotlin.test.*

class MetadataUnavailableMessageTest {
    @Test fun `each reason has its own explanation`() {
        val messages = MetadataUnavailableReason.entries.map { metadataUnavailableMessage(5, Unavailable(it)) }
        assertEquals(messages.size, messages.toSet().size)
        assertTrue(messages.all { "`5`" in it })
        assertContains(metadataUnavailableMessage(5, Unavailable(MetadataUnavailableReason.ACCESS_DENIED)), "/steambot")
    }

    @Test fun `retry estimates round up to whole minutes and mention background work`() {
        val waiting = metadataUnavailableMessage(5, Unavailable(MetadataUnavailableReason.WAITING_FOR_LOOKUP, 61_000, retrying = true))
        assertContains(waiting, "about 2 minutes.")
        assertContains(waiting, "retrying in the background")
        assertContains(metadataUnavailableMessage(5, Unavailable(MetadataUnavailableReason.PROCESSING, 30_000)), "about 1 minute.")
    }

    @Test fun `only unregistered viewers are pointed at signup`() {
        assertContains(metadataUnavailableMessage(5, Unavailable(MetadataUnavailableReason.NOT_STORED), canSignUp = true), "/signup")
        assertFalse("/signup" in metadataUnavailableMessage(5, Unavailable(MetadataUnavailableReason.NOT_STORED)))
    }
}
