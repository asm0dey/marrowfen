package tun.mux

import io.netty.buffer.ByteBuf

/** One mux frame per message, as `mux/PROTOCOL.md` frames it. [asMuxTransport] implements it over WebSocket. */
interface MuxTransport {
    /** Sends one message and takes ownership of it, sent or not. */
    suspend fun send(message: ByteBuf)

    /** The next message, owned by the caller, or `null` at EOF. Must be cancellable, and must return after [close]. */
    suspend fun receive(): ByteBuf?

    suspend fun close()
}
