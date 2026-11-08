package tun.mux

import kotlinx.coroutines.CoroutineScope

@DslMarker
annotation class MuxDsl

/** The server allocates stream ids; only it may [MuxSession.open]. */
enum class Role { SERVER, CLIENT }

/** One side's announced limits: its initial per-stream receive window and the largest DATA payload it accepts. */
data class MuxLimits(val window: UInt, val maxFrame: UInt)

/** A mux session as a value: nothing runs until [start]. */
class MuxSessionSpec internal constructor(val role: Role, val local: MuxLimits, val peer: MuxLimits)

/**
 * Runs for each stream the peer opens, in a child coroutine of the session, and owns the stream: when it returns or
 * throws, an unfinished stream is reset with `cancel`, and a finished one is released. Its exceptions go no further.
 */
typealias StreamHandler = suspend (MuxStream) -> Unit

@MuxDsl
class MuxSessionBuilder internal constructor() {
    private var role: Role? = null
    private var local: MuxLimits? = null
    private var peer: MuxLimits? = null

    fun role(role: Role) {
        this.role = role
    }

    /** What this side announced (in HELLO or HELLO_OK). */
    fun local(window: UInt, maxFrame: UInt) {
        local = limits(window, maxFrame)
    }

    /** What the peer announced. */
    fun peer(window: UInt, maxFrame: UInt) {
        peer = limits(window, maxFrame)
    }

    private fun limits(window: UInt, maxFrame: UInt): MuxLimits {
        require(maxFrame > 0u) { "maxFrame must be at least 1" }
        return MuxLimits(window, maxFrame)
    }

    internal fun build() = MuxSessionSpec(
        requireNotNull(role) { "muxSession { } needs role(SERVER) or role(CLIENT)" },
        requireNotNull(local) { "muxSession { } needs local(window, maxFrame)" },
        requireNotNull(peer) { "muxSession { } needs peer(window, maxFrame)" },
    )
}

fun muxSession(block: MuxSessionBuilder.() -> Unit): MuxSessionSpec = MuxSessionBuilder().apply(block).build()

/**
 * Runs the session over [transport] as a child of [scope] until the transport ends, a frame is undecodable or
 * [MuxSession.close] is called. Each stream the peer opens is handed to [onOpen].
 */
fun MuxSessionSpec.start(transport: MuxTransport, scope: CoroutineScope, onOpen: StreamHandler): MuxSession =
    MuxSession(this, transport, scope, onOpen).also { it.start() }
