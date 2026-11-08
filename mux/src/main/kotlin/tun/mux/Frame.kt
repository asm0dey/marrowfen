package tun.mux

import io.netty.buffer.ByteBuf
import kotlin.time.Duration

/** One mux frame, as specified byte for byte in `mux/PROTOCOL.md`. u32 fields are `UInt`s, u16 fields `UShort`s. */
sealed interface Frame {
    val streamId: UInt

    /** Client's first frame, on stream 0. [name] `null` asks for a random name. */
    data class Hello(val version: UShort, val token: String, val name: String?, val window: UInt, val maxFrame: UInt) :
        Frame {
        override val streamId get() = 0u
    }

    data class HelloOk(
        val name: String,
        val url: String,
        val window: UInt,
        val maxFrame: UInt,
        val pingInterval: Duration,
    ) : Frame {
        override val streamId get() = 0u
    }

    data class HelloErr(val code: UShort, val message: String) : Frame {
        override val streamId get() = 0u
    }

    data class Open(override val streamId: UInt, val headers: List<Header>, val end: Boolean = false) : Frame

    /** Response head, or trailers when it is not the first non-1xx `HEADERS` (then [end] must be set). */
    data class Headers(override val streamId: UInt, val headers: List<Header>, val end: Boolean = false) : Frame

    /** [payload] is owned by whoever holds the frame: `encode` takes it over, a decoded one must be released. */
    data class Data(override val streamId: UInt, val payload: ByteBuf, val end: Boolean = false) : Frame

    data class WindowUpdate(override val streamId: UInt, val increment: UInt) : Frame

    data class Reset(override val streamId: UInt, val code: ResetCode) : Frame
}

/** One header field. Encoding lowercases [name]; h2 pseudo-headers (`:method`, `:status`, ...) are plain entries. */
data class Header(val name: String, val value: String)

/** RESET codes 1–5, in declaration order: never reorder or insert, only append. */
enum class ResetCode {
    CANCEL,
    BACKEND_UNREACHABLE,
    PROTOCOL_ERROR,
    FLOW_CONTROL,
    REFUSED,
    ;

    val code: Int get() = ordinal + 1
}

/** A frame that breaks `mux/PROTOCOL.md`: malformed, oversized, unknown or not encodable. */
class MuxProtocolException(message: String) : RuntimeException(message)
