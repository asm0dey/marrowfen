package tun.mux

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Many streams over one [MuxTransport], by the rules in `mux/PROTOCOL.md` ("Streams and credit"). The reader only
 * decodes and enqueues, so neither a slow stream nor a full transport stalls it. One writer drains an unbounded
 * outbound queue that credit bounds in practice: DATA is queued only within the peer's window.
 */
class MuxSession internal constructor(
    private val spec: MuxSessionSpec,
    private val transport: MuxTransport,
    scope: CoroutineScope,
    private val onOpen: StreamHandler,
) {
    internal val lock = Any()
    internal val table = StreamTable(spec.role)
    internal val peerMaxFrame get() = spec.peer.maxFrame
    private val outbound = Channel<Frame>(Channel.UNLIMITED) { it.releasePayload() }
    private val job = SupervisorJob(scope.coroutineContext[Job]) // a failing stream handler fails only its stream
    private val children = CoroutineScope(scope.coroutineContext + job)

    internal fun start() {
        children.launch { writeLoop() }
        // UNDISPATCHED: the reader starts even if cancelled at once, so shutdown always runs
        children.launch(start = CoroutineStart.UNDISPATCHED) { readLoop() }
    }

    /** Opens a stream carrying request [headers]; [end] means no body follows. Server only. */
    fun open(headers: List<Header>, end: Boolean = false): MuxStream = synchronized(lock) {
        val stream = newStream(table.allocate())
        stream.localEnd = end
        enqueue(Frame.Open(stream.id, headers, end))
        stream
    }

    /**
     * Ends the session: streams fail with [CancellationException], buffers are released, the transport closes.
     * It is abrupt: queued frames are dropped, and the peer sees EOF, not RESETs.
     */
    suspend fun close() {
        job.cancel()
        job.join()
    }

    /** Suspends until the session has ended, for whatever reason. */
    suspend fun join() = job.join()

    internal fun enqueue(frame: Frame) {
        if (outbound.trySend(frame).isFailure) frame.releasePayload() // a failed trySend does not release
    }

    /** Takes a peer's OPEN: a new stream whose first [MuxStream.receive] is the request head. */
    internal fun accept(frame: Frame.Open) {
        if (!table.admit(frame.streamId)) return
        val stream = newStream(frame.streamId)
        stream.deliver(Frame.Headers(frame.streamId, frame.headers, frame.end), frame.end)
        children.launch {
            runCatching { onOpen(stream) } // a handler failure ends only its stream, never the thread
            stream.abandon()
        }
    }

    private fun newStream(id: UInt) = MuxStream(id, this, spec.local.window, spec.peer.window).also { table.add(it) }

    private suspend fun readLoop() {
        val ended = runCatching {
            while (true) dispatch(decode(transport.receive() ?: break, spec.local.maxFrame))
        }
        shutdown(ended.exceptionOrNull())
    }

    private suspend fun writeLoop() {
        val failed = runCatching {
            for (frame in outbound) transport.send(encodeWithin(frame, spec.peer.maxFrame) ?: continue)
        }.exceptionOrNull()
        // a failed send ends the session: closing the transport makes the reader's receive return, then it shuts down
        if (failed !is CancellationException) withContext(NonCancellable) { transport.close() }
        job.cancel()
    }

    private suspend fun shutdown(cause: Throwable?) {
        val closing = CancellationException("mux session closed", cause)
        synchronized(lock) { table.close() }.forEach { it.fail(closing) }
        outbound.cancel()
        job.cancel()
        withContext(NonCancellable) { transport.close() }
    }
}
