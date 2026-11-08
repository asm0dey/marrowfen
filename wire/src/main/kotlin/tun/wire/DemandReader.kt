package tun.wire

import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.FixedRecvByteBufAllocator
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel as Queue

/** Size of one recv buffer, and so the most inbound bytes a channel holds while nobody reads. */
internal const val RECV_BUFFER_BYTES = 16 * 1024

/**
 * Tail inbound handler. The backpressure rule: `autoRead` is off and one read yields at most one recv buffer,
 * so Netty asks the socket for bytes only when [read] finds the inbox empty.
 */
internal class DemandReader : ChannelInboundHandlerAdapter() {
    private val bufferedBytes = AtomicLong()
    private val inbox = Queue<ByteBuf>(Queue.UNLIMITED, onUndeliveredElement = ::drop)

    val buffered: Long get() = bufferedBytes.get()

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        ctx.channel().config().setAutoRead(false)
            .setRecvByteBufAllocator(FixedRecvByteBufAllocator(RECV_BUFFER_BYTES).maxMessagesPerRead(1))
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg !is ByteBuf) {
            ReferenceCountUtil.release(msg)
            error("wire carries bytes only, got ${msg.javaClass.name}")
        }
        bufferedBytes.addAndGet(msg.readableBytes().toLong())
        if (inbox.trySend(msg).isFailure) drop(msg) // closed: trySend does not call onUndeliveredElement
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        inbox.close()
        ctx.fireChannelInactive()
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        inbox.close(cause)
        ctx.close()
    }

    suspend fun read(channel: Channel): ByteBuf? {
        inbox.tryReceive().getOrNull()?.let { return taken(it) }
        channel.read() // demand: one Netty read per suspension
        return receive(channel)?.let(::taken)
    }

    // A discarded inbox ends a receive with CancellationException: that is EOF. Only our own cancellation propagates.
    private suspend fun receive(channel: Channel): ByteBuf? = try {
        val result = inbox.receiveCatching()
        result.exceptionOrNull()?.let { if (it !is CancellationException) throw it }
        result.getOrNull()
    } catch (e: CancellationException) {
        if (!currentCoroutineContext().isActive) {
            discard()
            channel.close()
            throw e
        }
        null
    }

    /** Releases everything queued now and everything that arrives later. */
    fun discard() {
        inbox.cancel()
    }

    private fun taken(buf: ByteBuf): ByteBuf = buf.also { bufferedBytes.addAndGet(-it.readableBytes().toLong()) }

    private fun drop(buf: ByteBuf) {
        bufferedBytes.addAndGet(-buf.readableBytes().toLong())
        buf.release()
    }
}
