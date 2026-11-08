package tun.mux

import io.netty.buffer.ByteBuf
import kotlin.time.Duration.Companion.milliseconds

// The decoding half of FrameCodec.kt: reads one frame, failing with MuxProtocolException. Never releases.

internal fun ByteBuf.readFrame(maxFrame: Int): Frame {
    need(HEADER_BYTES)
    val streamId = readU31("streamId")
    val type = readUnsignedByte().toInt()
    val flags = readUnsignedByte().toInt()
    if (readableBytes() > maxFrame) fail("payload of ${readableBytes()} bytes exceeds maxFrame $maxFrame")
    checkHeader(streamId, type, flags)
    val frame = readPayload(streamId, type, flags == END)
    if (isReadable) fail("${readableBytes()} trailing bytes after type $type")
    return frame
}

private fun checkHeader(streamId: Int, type: Int, flags: Int) {
    if (type > RESET) fail("unknown type $type")
    if (flags and END.inv() != 0) fail("reserved flag bits set: $flags")
    if (flags == END && type !in OPEN..DATA) fail("END on type $type")
    if ((type <= HELLO_ERR) != (streamId == 0)) fail("type $type on stream $streamId")
}

private fun ByteBuf.readPayload(streamId: Int, type: Int, end: Boolean): Frame = when (type) {
    HELLO -> Frame.Hello(readU16(), readStr(), readStr().ifEmpty { null }, readU31("window"), readU31("maxFrame"))
    HELLO_OK -> Frame.HelloOk(
        readStr(), readStr(), readU31("window"), readU31("maxFrame"), readU31("pingInterval").milliseconds,
    )
    HELLO_ERR -> Frame.HelloErr(readU16(), readStr())
    OPEN -> Frame.Open(streamId, readHeaderBlock(), end)
    HEADERS -> Frame.Headers(streamId, readHeaderBlock(), end)
    DATA -> Frame.Data(streamId, readRetainedSlice(readableBytes()), end)
    WINDOW_UPDATE -> Frame.WindowUpdate(streamId, readU31("increment"))
    else -> Frame.Reset(streamId, readResetCode())
}

private fun ByteBuf.readHeaderBlock(): List<Header> {
    val headers = ArrayList<Header>()
    while (isReadable) headers.add(Header(readStr(), readStr()))
    return headers
}

private fun ByteBuf.readResetCode(): ResetCode {
    val code = readU16()
    return ResetCode.entries.firstOrNull { it.code == code } ?: fail("unknown RESET code $code")
}

private fun ByteBuf.readU16(): Int {
    need(Short.SIZE_BYTES)
    return readUnsignedShort()
}

private fun ByteBuf.readU31(field: String): Int {
    need(Int.SIZE_BYTES)
    return readInt().also { if (it < 0) fail("$field is 2^31 or more") }
}

private fun ByteBuf.readStr(): String {
    val length = readU16()
    need(length)
    return readCharSequence(length, Charsets.UTF_8).toString()
}

private fun ByteBuf.need(bytes: Int) {
    if (readableBytes() < bytes) fail("truncated: needs $bytes bytes, has ${readableBytes()}")
}

private fun fail(message: String): Nothing = throw MuxProtocolException(message)
