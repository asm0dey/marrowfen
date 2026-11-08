package tun.wire

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.FixedRecvByteBufAllocator
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel as Queue

/**
 * [DemandReader] for decoded messages (HTTP objects, WebSocket frames): the same backpressure rule, at message level.
 * A read that a handler upstream consumes whole (a ping, a fragment) yields nothing here, so a waiting reader re-reads.
 */
internal class MessageReader : ChannelInboundHandlerAdapter() {
    private val inbox = Queue<Any>(Queue.UNLIMITED, onUndeliveredElement = ReferenceCountUtil::release)

    @Volatile
    private var waiting = false

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        ctx.channel().config().setAutoRead(false)
            .setRecvByteBufAllocator(FixedRecvByteBufAllocator(RECV_BUFFER_BYTES).maxMessagesPerRead(1))
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        waiting = false
        if (inbox.trySend(msg).isFailure) ReferenceCountUtil.release(msg) // closed: trySend does not release
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        if (waiting) ctx.read() // nothing reached us: demand is still open
        ctx.fireChannelReadComplete()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        inbox.close()
        ctx.fireChannelInactive()
    }

    // a decoder or limit failure (oversize, protocol violation) ends the connection; the reader sees EOF
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        inbox.close()
        ctx.close()
    }

    /** The next message, owned by the caller, or `null` once the channel has closed or failed. */
    suspend fun read(channel: Channel): Any? {
        inbox.tryReceive().getOrNull()?.let { return it }
        waiting = true
        channel.read()
        return try {
            inbox.receiveCatching().getOrNull()
        } catch (e: CancellationException) { // a discarded inbox is EOF; only our own cancellation propagates
            if (!currentCoroutineContext().isActive) {
                discard()
                channel.close()
                throw e
            }
            null
        } finally {
            waiting = false
        }
    }

    /** Releases everything queued now and everything that arrives later. */
    fun discard() {
        inbox.cancel()
    }
}
