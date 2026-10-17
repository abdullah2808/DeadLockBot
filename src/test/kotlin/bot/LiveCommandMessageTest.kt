package bot

import kotlin.test.*

class LiveCommandMessageTest {
    @Test
    fun `absence from the Watch list is not reported as offline unless Steam presence says so`() {
        assertContains(notLiveMessage("P", true), "is in Deadlock")
        assertContains(notLiveMessage("P", false), "isn't playing Deadlock")
        assertContains(notLiveMessage("P", null), "subset of live games")
        assertContains(liveUnavailableMessage("P", true), "is in Deadlock right now")
        assertContains(liveUnavailableMessage("P", null), "couldn't reach")
    }
}
