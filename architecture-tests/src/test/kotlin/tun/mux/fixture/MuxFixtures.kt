package tun.mux.fixture

import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import tun.server.Server

// Violation fixture: mux may not use Netty beyond io.netty.buffer.
object MuxUsesChannel {
    fun close(channel: Channel) {
        channel.close()
    }
}

// Allowed: the io.netty.buffer exception. Must not be reported.
object MuxUsesBuffer {
    fun size(buf: ByteBuf) = buf.readableBytes()
}

// Violation fixture: mux sits below the composition roots.
object MuxUsesServer {
    fun server() = Server
}
