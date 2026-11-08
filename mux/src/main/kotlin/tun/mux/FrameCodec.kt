package tun.mux

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import kotlin.time.Duration

// Byte layouts: mux/PROTOCOL.md. All integers big-endian, which is ByteBuf's default order.
internal const val HEADER_BYTES = 6
internal const val END = 0x01
private const val MAX_STR_BYTES = 0xFFFF

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
 * Throws [MuxProtocolException] for a string over 65535 UTF-8 bytes or a ping interval over u32 ms, leaking nothing.
 */
fun encode(frame: Frame, alloc: ByteBufAllocator): ByteBuf {
    val buf = alloc.buffer(HEADER_BYTES)
    try {
        buf.writeU32(frame.streamId).writeByte(typeOf(frame)).writeByte(flagsOf(frame))
        if (frame is Frame.Data) return Unpooled.wrappedBuffer(buf, frame.payload)
        buf.writePayload(frame)
    } catch (e: MuxProtocolException) {
        buf.release()
        throw e
    }
    return buf
}

/**
 * Decodes one whole message whose payload may be at most [maxFrame] bytes. Always releases [buf]; a decoded
 * [Frame.Data] holds a retained slice of it that the caller must release. Throws [MuxProtocolException].
 */
fun decode(buf: ByteBuf, maxFrame: UInt): Frame {
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
            .writeU32(frame.window).writeU32(frame.maxFrame)
        is Frame.HelloOk -> writeStr(frame.name).writeStr(frame.url).writeU32(frame.window).writeU32(frame.maxFrame)
            .writeMillis(frame.pingInterval)
        is Frame.HelloErr -> writeU16(frame.code).writeStr(frame.message)
        is Frame.Open -> writeHeaderBlock(frame.headers)
        is Frame.Headers -> writeHeaderBlock(frame.headers)
        is Frame.WindowUpdate -> writeU32(frame.increment)
        is Frame.Reset -> writeShort(frame.code.code)
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
    writeShort(length)
    ByteBufUtil.writeUtf8(this, s)
    return this
}

private fun ByteBuf.writeU16(value: UShort): ByteBuf = writeShort(value.toInt())

private fun ByteBuf.writeU32(value: UInt): ByteBuf = writeInt(value.toInt())

private fun ByteBuf.writeMillis(interval: Duration): ByteBuf {
    val millis = interval.inWholeMilliseconds
    if (millis !in 0..UInt.MAX_VALUE.toLong()) throw MuxProtocolException("interval of $millis ms is not a u32")
    return writeU32(millis.toUInt())
}
