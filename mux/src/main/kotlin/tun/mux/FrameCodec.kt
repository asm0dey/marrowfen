package tun.mux

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import kotlin.time.Duration

// Byte layouts: mux/PROTOCOL.md. All integers big-endian, which is ByteBuf's default order.
internal const val HEADER_BYTES = 6
internal const val END = 0x01
private const val MAX_STR_BYTES = 0xFFFF // also the u16 maximum

internal const val HELLO = 0
internal const val HELLO_OK = 1
internal const val HELLO_ERR = 2
internal const val OPEN = 3
internal const val HEADERS = 4
internal const val DATA = 5
internal const val WINDOW_UPDATE = 6
internal const val RESET = 7

private val HOP_BY_HOP = setOf("connection", "keep-alive", "transfer-encoding", "upgrade", "te")

/**
 * Encodes [frame] as one message. Takes ownership of a [Frame.Data] payload, which is wrapped, not copied.
 * Throws [MuxProtocolException] for a string over 65535 UTF-8 bytes or an integer out of its range, leaking nothing.
 */
fun encode(frame: Frame, alloc: ByteBufAllocator): ByteBuf {
    val buf = alloc.buffer(HEADER_BYTES)
    try {
        buf.writeU31(frame.streamId).writeByte(typeOf(frame)).writeByte(flagsOf(frame))
        if (frame is Frame.Data) return Unpooled.wrappedBuffer(buf, frame.payload)
        buf.writePayload(frame)
    } catch (e: MuxProtocolException) {
        buf.release()
        (frame as? Frame.Data)?.payload?.release()
        throw e
    }
    return buf
}

/**
 * Decodes one whole message whose payload may be at most [maxFrame] bytes. Always releases [buf]; a decoded
 * [Frame.Data] holds a retained slice of it that the caller must release. Throws [MuxProtocolException].
 */
fun decode(buf: ByteBuf, maxFrame: Int): Frame {
    try {
        return buf.readFrame(maxFrame)
    } finally {
        buf.release()
    }
}

private fun typeOf(frame: Frame): Int = when (frame) {
    is Frame.Hello -> HELLO
    is Frame.HelloOk -> HELLO_OK
    is Frame.HelloErr -> HELLO_ERR
    is Frame.Open -> OPEN
    is Frame.Headers -> HEADERS
    is Frame.Data -> DATA
    is Frame.WindowUpdate -> WINDOW_UPDATE
    is Frame.Reset -> RESET
}

private fun flagsOf(frame: Frame): Int {
    val end = when (frame) {
        is Frame.Open -> frame.end
        is Frame.Headers -> frame.end
        is Frame.Data -> frame.end
        else -> false
    }
    return if (end) END else 0
}

private fun ByteBuf.writePayload(frame: Frame) {
    when (frame) {
        is Frame.Hello -> writeU16(frame.version).writeStr(frame.token).writeStr(frame.name.orEmpty())
            .writeU31(frame.window).writeU31(frame.maxFrame)
        is Frame.HelloOk -> writeStr(frame.name).writeStr(frame.url).writeU31(frame.window).writeU31(frame.maxFrame)
            .writeMillis(frame.pingInterval)
        is Frame.HelloErr -> writeU16(frame.code).writeStr(frame.message)
        is Frame.Open -> writeHeaderBlock(frame.headers)
        is Frame.Headers -> writeHeaderBlock(frame.headers)
        is Frame.WindowUpdate -> writeU31(frame.increment)
        is Frame.Reset -> writeU16(frame.code.code)
        is Frame.Data -> error("DATA is wrapped, not written")
    }
}

private fun ByteBuf.writeHeaderBlock(headers: List<Header>) {
    for (header in headers) {
        val name = header.name.lowercase()
        if (!isHopByHop(name, header.value)) writeStr(name).writeStr(header.value)
    }
}

private fun isHopByHop(name: String, value: String): Boolean =
    name in HOP_BY_HOP && !(name == "te" && value.trim().equals("trailers", ignoreCase = true))

private fun ByteBuf.writeStr(s: String): ByteBuf {
    val length = ByteBufUtil.utf8Bytes(s)
    if (length > MAX_STR_BYTES) throw MuxProtocolException("string of $length bytes exceeds $MAX_STR_BYTES")
    writeU16(length)
    ByteBufUtil.writeUtf8(this, s)
    return this
}

private fun ByteBuf.writeU16(value: Int): ByteBuf {
    if (value !in 0..MAX_STR_BYTES) throw MuxProtocolException("u16 value $value out of range")
    return writeShort(value)
}

private fun ByteBuf.writeU31(value: Int): ByteBuf {
    if (value < 0) throw MuxProtocolException("u32 value $value out of range 0..2^31-1")
    return writeInt(value)
}

private fun ByteBuf.writeMillis(interval: Duration): ByteBuf {
    val millis = interval.inWholeMilliseconds
    if (millis !in 0..Int.MAX_VALUE) throw MuxProtocolException("interval of $millis ms out of range 0..2^31-1")
    return writeInt(millis.toInt())
}
