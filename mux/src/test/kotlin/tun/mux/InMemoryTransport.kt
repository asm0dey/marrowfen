package tun.mux

import io.netty.buffer.ByteBuf
import kotlinx.coroutines.channels.Channel

/** One end of a connected in-memory pair. Closing either end gives the other EOF and releases undelivered messages. */
class InMemoryTransport private constructor(
    private val incoming: Channel<ByteBuf>,
    private val outgoing: Channel<ByteBuf>,
) : MuxTransport {
    override suspend fun send(message: ByteBuf) = outgoing.send(message) // a closed channel releases it and throws

    override suspend fun receive(): ByteBuf? = incoming.receiveCatching().getOrNull()

    override suspend fun close() {
        incoming.cancel()
        outgoing.cancel()
    }

    companion object {
        fun pair(): Pair<InMemoryTransport, InMemoryTransport> {
            val ab = messages()
            val ba = messages()
            return InMemoryTransport(ba, ab) to InMemoryTransport(ab, ba)
        }

        private fun messages() = Channel<ByteBuf>(Channel.UNLIMITED) { it.release() }
    }
}
