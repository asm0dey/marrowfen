package tun.mux

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator

// The writer's check on each outbound frame, so one bad frame fails its stream, not the session or the peer's decode.

/**
 * [frame] encoded, or `null` when it cannot be encoded (a string over 65535 bytes) or its payload exceeds the peer's
 * [maxFrame] (an oversized head). Then the frame is released and its stream is reset with `protocol_error`.
 */
internal fun MuxSession.encodeWithin(frame: Frame, maxFrame: UInt): ByteBuf? {
    val message = try {
        encode(frame, ByteBufAllocator.DEFAULT)
    } catch (_: MuxProtocolException) {
        null
    }
    if (message != null && (message.readableBytes() - HEADER_BYTES).toUInt() <= maxFrame) return message
    message?.release()
    refuse(frame.streamId)
    return null
}

private fun MuxSession.refuse(id: UInt) {
    val stream = synchronized(lock) { table.get(id) }
    if (stream != null) stream.reset(ResetCode.PROTOCOL_ERROR) else enqueue(Frame.Reset(id, ResetCode.PROTOCOL_ERROR))
}
