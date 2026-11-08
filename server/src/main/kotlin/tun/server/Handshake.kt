package tun.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull
import tun.core.ClaimResult
import tun.core.Registry
import tun.mux.Frame
import tun.mux.MuxProtocolException
import tun.mux.MuxSession
import tun.mux.MuxTransport
import tun.mux.Role
import tun.mux.muxSession
import tun.mux.receiveFrame
import tun.mux.releasePayload
import tun.mux.sendFrame
import tun.mux.start
import kotlin.time.Duration

/** The server's announced per-stream window, in bytes. */
internal const val WINDOW = 262_144u

/** The largest DATA payload the server accepts, in bytes. */
internal const val MAX_FRAME = 16_384u

private const val VERSION: UShort = 1u

/** Raised in a visitor waiting on a tunnel whose connection ended before HELLO_OK. */
internal class TunnelClosed : Exception("tunnel closed before HELLO_OK")

/** The control handshake of `mux/PROTOCOL.md` ("Handshake"), then the mux session until the connection ends. */
internal class Handshake(
    private val config: ServerConfig,
    private val registry: Registry<Tunnel>,
    private val helloTimeout: Duration,
) {
    /** Runs on the connection's own coroutine, whose `Job` holds the claimed name until the connection ends. */
    suspend fun run(transport: MuxTransport) {
        val tunnel = CompletableDeferred<MuxSession>()
        try {
            // EOF, timeout or undecodable: close without a reply
            val hello = firstFrame(transport)?.let { accept(it, transport) } ?: return
            when (val claim = registry.claim(hello.token, currentCoroutineContext().job, tunnel)) {
                is ClaimResult.Claimed -> session(hello, claim.name, transport, tunnel)
                ClaimResult.Rejected -> transport.sendFrame(UNKNOWN_TOKEN)
            }
        } finally {
            tunnel.completeExceptionally(TunnelClosed()) // no-op once the session was handed out
            transport.close()
        }
    }

    private suspend fun firstFrame(transport: MuxTransport): Frame? = try {
        withTimeoutOrNull(helloTimeout) { transport.receiveFrame(MAX_FRAME) }
    } catch (_: MuxProtocolException) {
        null
    }

    /** The HELLO if it may proceed to the token check, or `null` after answering HELLO_ERR. */
    private suspend fun accept(first: Frame, transport: MuxTransport): Frame.Hello? {
        val refusal = when {
            first !is Frame.Hello -> BAD_HELLO.also { first.releasePayload() }
            first.version != VERSION -> Frame.HelloErr(1u, "unsupported protocol version ${first.version}; upgrade tun")
            first.window == 0u || first.maxFrame < MAX_FRAME -> BAD_HELLO
            else -> return first
        }
        transport.sendFrame(refusal)
        return null
    }

    private suspend fun session(hello: Frame.Hello, name: String, transport: MuxTransport, tunnel: Tunnel) {
        val url = "https://$name.${config.domain}"
        transport.sendFrame(Frame.HelloOk(name, url, WINDOW, MAX_FRAME, config.pingInterval))
        coroutineScope {
            val spec = muxSession {
                role(Role.SERVER)
                local(WINDOW, MAX_FRAME)
                peer(hello.window, hello.maxFrame)
            }
            val session = spec.start(transport, this) {} // the server ignores every OPEN
            tunnel.complete(session)
            session.join()
        }
    }

    private companion object {
        // the requested name is ignored in v1: a random one is assigned
        val UNKNOWN_TOKEN = Frame.HelloErr(2u, "unknown token; ask the server operator for a token, then run tun login")
        val BAD_HELLO = Frame.HelloErr(3u, "bad HELLO: the first frame must be a valid HELLO; upgrade tun")
    }
}
