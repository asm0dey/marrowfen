package tun.server.fixture

import io.netty.channel.Channel

// Violation fixture: Netty above the wrapper.
object ServerUsesNetty {
    fun close(channel: Channel) {
        channel.close()
    }
}
