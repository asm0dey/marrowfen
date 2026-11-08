package tun.wire

import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One TCP connection as suspend functions. Netty reads only while a coroutine waits in [read]. */
class WireChannel internal constructor(internal val netty: Channel, private val reader: DemandReader) {
    /** Inbound bytes queued but not yet returned by [read]. */
    internal val buffered: Long get() = reader.buffered

    /** The next inbound buffer, owned by the caller, or `null` at EOF. Throws the cause if the channel failed. */
    suspend fun read(): ByteBuf? = reader.read(netty)

    /**
     * Writes and flushes [buf], suspending until it is written. [buf] belongs to the wrapper from the call on.
     * Cancelling a suspended write closes the whole connection, so Netty fails and releases what is still pending.
     */
    suspend fun write(buf: ByteBuf) {
        netty.writeAndFlush(buf).awaitOrClose()
    }

    suspend fun close() {
        reader.discard()
        netty.close().awaitOrClose()
    }

    /** Non-suspending close for completion handlers: releases the inbox and closes the channel. */
    internal fun abort() {
        reader.discard()
        netty.close()
    }
}

/** Awaits the future; cancellation closes its channel, so Netty fails and releases whatever is still pending. */
internal suspend fun ChannelFuture.awaitOrClose(): Channel = suspendCancellableCoroutine { cont ->
    addListener { if (it.isSuccess) cont.resume(channel()) else cont.resumeWithException(it.cause()) }
    cont.invokeOnCancellation { channel().close() }
}
