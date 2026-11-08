package tun.core.fixture

import io.netty.buffer.ByteBuf
import tun.mux.Mux

// Violation fixtures: they exist to prove the AD-1 rules fire. Never used.
object CoreUsesNetty {
    fun size(buf: ByteBuf) = buf.readableBytes()
}

object CoreUsesMux {
    fun mux() = Mux
}
