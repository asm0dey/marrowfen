package tun.mux

import io.netty.buffer.ByteBuf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel

/** A stream's abnormal end, sent or received as `RESET` with [code]. */
class MuxStreamReset(val code: ResetCode) : RuntimeException("stream reset: $code")

/**
 * One multiplexed stream. Peer frames queue in an inbox bounded by the credit this side grants, which [ack] returns;
 * [write] is bounded by the credit the peer grants. Cancelling a coroutine suspended in [receive] or [write] resets
 * the stream with `cancel`. State is guarded by [MuxSession.lock].
 */
class MuxStream internal constructor(
    val id: UInt,
    private val session: MuxSession,
    receiveWindow: UInt,
    sendWindow: UInt,
) {
    internal val inbox = Channel<Frame>(Channel.UNLIMITED) { it.releasePayload() }
    internal val credit = Channel<Unit>(Channel.CONFLATED) // signalled when sendWindow grows or the stream fails
    internal var receiveWindow = receiveWindow.toLong()
    internal var sendWindow = sendWindow.toLong()
    internal var localEnd = false
    internal var remoteEnd = false
    internal var unacked = 0L // DATA bytes delivered and not yet acked
    internal var heads = 0 // non-END HEADERS received
    internal val done get() = localEnd && remoteEnd

    @Volatile
    internal var failure: Throwable? = null
    private val retired get() = done && inbox.isClosedForReceive

    /**
     * The next [Frame.Headers] or [Frame.Data] from the peer, or `null` after its END. For a stream the peer opened,
     * the first is the OPEN's head. The caller owns and releases a DATA payload, then [ack]s its bytes.
     * Throws [MuxStreamReset] after a reset, or [CancellationException] once the session has closed.
     */
    suspend fun receive(): Frame? {
        val next = try {
            inbox.receiveCatching()
        } catch (e: CancellationException) {
            reset(ResetCode.CANCEL)
            throw e
        }
        retireIfDone()
        if (next.isClosed) failure?.let { throw it }
        return next.getOrNull()
    }

    /** Returns [bytes] of credit to the peer. Call it only once those bytes have reached the next hop. */
    fun ack(bytes: Int) {
        synchronized(session.lock) {
            require(bytes in 0..unacked) { "cannot ack $bytes bytes: $unacked delivered and not yet acked" }
            unacked -= bytes
            if (bytes == 0 || remoteEnd || failure != null) return
            receiveWindow += bytes
            session.enqueue(Frame.WindowUpdate(id, bytes.toUInt()))
        }
    }

    /**
     * Sends [buf] as DATA, taking ownership, in frames of at most the peer's credit and maxFrame; suspends while the
     * credit is 0. [end] half-closes after the last byte. Throws [IllegalStateException] after this side's END.
     * A stream allows one writer at a time.
     */
    suspend fun write(buf: ByteBuf, end: Boolean = false) {
        try {
            do {
                if (!sendChunk(buf, end)) credit.receive()
            } while (buf.isReadable)
        } catch (e: CancellationException) {
            reset(ResetCode.CANCEL)
            throw e
        } finally {
            buf.release()
        }
    }

    /** Sends a response head or (with [end]) trailers. Throws [IllegalStateException] after this side's END. */
    fun headers(headers: List<Header>, end: Boolean = false) = synchronized(session.lock) {
        checkWritable()
        localEnd = end
        session.enqueue(Frame.Headers(id, headers, end))
        retireIfDone()
    }

    /** Ends the stream abnormally: the peer's receive and write throw [MuxStreamReset]. Releases queued frames. */
    fun reset(code: ResetCode) {
        if (fail(MuxStreamReset(code))) session.enqueue(Frame.Reset(id, code))
    }

    /** Fails the stream with [cause] and releases its inbox; `false` if it had already ended. */
    internal fun fail(cause: Throwable): Boolean = synchronized(session.lock) {
        if (failure != null || retired) return false
        failure = cause
        inbox.cancel()
        credit.trySend(Unit)
        session.table.forget(this)
        true
    }

    /** The handler is finished with the stream: reset it with `cancel` if unfinished, else release it silently. */
    internal fun abandon() = synchronized(session.lock) {
        if (done) fail(CancellationException("stream handler returned")) else reset(ResetCode.CANCEL)
    }

    internal fun retireIfDone() = synchronized(session.lock) {
        if (retired) session.table.forget(this)
    }

    /** Queues the next chunk of [buf] if credit allows; `false` when the writer must wait for credit. */
    private fun sendChunk(buf: ByteBuf, end: Boolean): Boolean = synchronized(session.lock) {
        checkWritable()
        if (!buf.isReadable && !end) return true // nothing to send
        val n = chunk(sendWindow, session.peerMaxFrame, buf.readableBytes())
        if (n == 0 && buf.isReadable) return false
        sendWindow -= n
        localEnd = end && n == buf.readableBytes()
        session.enqueue(Frame.Data(id, buf.readRetainedSlice(n), localEnd))
        retireIfDone()
        true
    }

    private fun checkWritable() {
        failure?.let { throw it }
        check(!localEnd) { "stream $id has already sent END" }
    }
}
