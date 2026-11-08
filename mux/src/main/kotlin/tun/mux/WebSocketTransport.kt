package tun.mux

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import tun.wire.WebSocket

/** The WebSocket as a mux transport: one binary message per frame. Used by both the server and the client. */
fun WebSocket.asMuxTransport(): MuxTransport = object : MuxTransport {
    override suspend fun send(message: ByteBuf) = this@asMuxTransport.send(message)

    override suspend fun receive(): ByteBuf? = this@asMuxTransport.receive()

    override suspend fun close() = this@asMuxTransport.close()
}

/** The next message decoded with payloads up to [maxFrame], or `null` at EOF. Throws [MuxProtocolException]. */
suspend fun MuxTransport.receiveFrame(maxFrame: UInt): Frame? = receive()?.let { decode(it, maxFrame) }

/** Encodes and sends [frame]; for the stream-0 handshake, before a [MuxSession] owns the transport. */
suspend fun MuxTransport.sendFrame(frame: Frame) = send(encode(frame, ByteBufAllocator.DEFAULT))
