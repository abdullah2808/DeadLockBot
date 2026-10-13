package api.gc

import com.google.protobuf.Message
import kotlinx.coroutines.CompletableDeferred

/** One outstanding GC request; match response type and echoed Steam job ID when present. */
internal class GcResponseRouter {
    private data class Pending(val type: Int, val job: Long, val answer: CompletableDeferred<Message>)
    private var pending: Pending? = null

    @Synchronized fun expect(type: Int, job: Long): CompletableDeferred<Message> {
        check(pending == null) { "Another GC request is still waiting" }
        return CompletableDeferred<Message>().also { pending = Pending(type, job, it) }
    }

    @Synchronized fun complete(type: Int, targetJob: Long, message: Message) {
        val waiting = pending ?: return
        if (waiting.type != type) return
        // Some GC replies omit the job header. Requests are serialized and timeouts/cancellation reconnect.
        if (targetJob != -1L && targetJob != 0L && targetJob != waiting.job) return
        waiting.answer.complete(message)
    }

    @Synchronized fun clear(answer: CompletableDeferred<Message>) {
        if (pending?.answer === answer) pending = null
    }

    @Synchronized fun fail(error: Exception) {
        pending?.answer?.completeExceptionally(error)
        pending = null
    }
}
