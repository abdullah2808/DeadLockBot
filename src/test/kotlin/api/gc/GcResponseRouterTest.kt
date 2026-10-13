package api.gc

import gc.citadel.CitadelMatchHistory.CMsgClientHello
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.*

class GcResponseRouterTest {
    private val message = CMsgClientHello.getDefaultInstance()
    @Test fun `wrong message type and wrong echoed job cannot satisfy request`() = runTest {
        val router = GcResponseRouter()
        val answer = router.expect(9168, 42)
        router.complete(9113, 42, message)
        router.complete(9168, 41, message)
        assertFalse(answer.isCompleted)
        router.complete(9168, 42, message)
        assertEquals(message, answer.await())
        assertFailsWith<IllegalStateException> { router.expect(9113, 43) }
        router.clear(answer)
        assertFalse(router.expect(9113, 43).isCompleted)
    }
    @Test fun `serialized untagged replies are supported and late tagged replies are ignored`() = runTest {
        val router = GcResponseRouter()
        val old = router.expect(9168, 42)
        router.clear(old)
        val current = router.expect(9168, 43)
        router.clear(old)
        router.complete(9168, 42, message)
        assertFalse(current.isCompleted)
        router.complete(9168, -1, message)
        assertEquals(message, current.await())
    }
    @Test fun `disconnect fails waiter and allows a new request`() = runTest {
        val router = GcResponseRouter()
        val answer = router.expect(9168, 42)
        router.fail(IOException("disconnected"))
        assertFailsWith<IOException> { answer.await() }
        assertFalse(router.expect(9168, 43).isCompleted)
    }
}
