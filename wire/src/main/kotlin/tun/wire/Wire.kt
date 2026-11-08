package tun.wire

import io.netty.channel.EventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import java.util.concurrent.TimeUnit

/** Owns the one NIO event loop group. No thread starts until the first [start] or [connect]. */
class Wire : AutoCloseable {
    private val group = lazy { MultiThreadIoEventLoopGroup(NioIoHandler.newFactory()) }

    internal val loop: EventLoopGroup get() = group.value

    /** True once an event loop group exists, i.e. after the first [start] or [connect]. */
    internal val started: Boolean get() = group.isInitialized()

    /**
     * Shuts the group down gracefully and waits: its channels close and Netty releases their pending writes.
     * Inbound buffers queued in a [WireChannel] stay until its owner closes it (or reads them).
     */
    override fun close() {
        if (started) group.value.shutdownGracefully(0, SHUTDOWN_TIMEOUT_S, TimeUnit.SECONDS).syncUninterruptibly()
    }

    private companion object {
        const val SHUTDOWN_TIMEOUT_S = 5L
    }
}
