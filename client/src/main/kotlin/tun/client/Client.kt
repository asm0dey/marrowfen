package tun.client

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tun.mux.Frame
import tun.mux.MuxProtocolException
import tun.mux.MuxTransport
import tun.mux.Role
import tun.mux.asMuxTransport
import tun.mux.muxSession
import tun.mux.receiveFrame
import tun.mux.releasePayload
import tun.mux.sendFrame
import tun.mux.start
import tun.wire.WebSocket
import tun.wire.WebSocketConnectException
import tun.wire.Wire
import tun.wire.connectWebSocket
import java.io.IOException
import java.io.PrintStream
import java.net.URI
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The client's announced per-stream window, in bytes; the same as the server's. */
private const val WINDOW = 262_144u

/** The largest DATA payload the client accepts, in bytes; the same as the server's. */
private const val MAX_FRAME = 16_384u

/** A whole mux frame: header plus [MAX_FRAME] payload. */
private val MAX_MESSAGE = MAX_FRAME.toInt() + 6

private const val VERSION: UShort = 1u
private const val USAGE = "usage: tun http <port>"
private val PORTS = 1..65535

/**
 * The `http` mode: connects to the saved server, prints the public URL, and keeps the session up until the
 * connection ends. Every return is a failure, reported to [err] as one line naming the fix.
 */
fun runHttp(
    args: List<String>,
    out: PrintStream,
    err: PrintStream,
    configFile: Path,
    helloTimeout: Duration = 10.seconds,
): Int {
    // the port is where Story 1.8 relays streams to; until then it is only checked
    if (args.singleOrNull()?.toIntOrNull()?.takeIf { it in PORTS } == null) return 2.also { err.println(USAGE) }
    val failure = loadLogin(configFile)?.let { login ->
        Wire().use { wire -> runBlocking { tunnel(wire, login, out, helloTimeout) } }
    } ?: "not logged in; run tun login <server-url> <token>"
    err.println("tun: $failure")
    return 1
}

/** A connect failure found after the upgrade; [why] goes in the cannot-connect line. */
private class CannotConnect(val why: String) : Exception(why)

/** Runs the tunnel and returns why it ended, for the `tun: ` line. Never mentions the token. */
private suspend fun tunnel(wire: Wire, login: Login, out: PrintStream, helloTimeout: Duration): String {
    val why = try {
        val url = URI("${login.server}/_tun/connect")
        val socket = withTimeoutOrNull(helloTimeout) { wire.connectWebSocket(url, MAX_MESSAGE) }
            ?: throw CannotConnect("no reply within $helloTimeout")
        return serve(socket, login, out, helloTimeout)
    } catch (e: WebSocketConnectException) {
        e.message.orEmpty()
    } catch (e: CannotConnect) {
        e.why
    } catch (e: IOException) {
        e.message ?: e.javaClass.simpleName
    } catch (e: MuxProtocolException) {
        "bad reply: ${e.message}"
    }
    return "cannot connect to ${login.server} ($why); check the server URL, or run tun login again"
}

/** HELLO, then on HELLO_OK the URL on [out] and the session until it ends. Returns the line for how it ended. */
private suspend fun serve(socket: WebSocket, login: Login, out: PrintStream, helloTimeout: Duration): String {
    val transport = socket.asMuxTransport()
    try {
        val reply = hello(transport, login.token, helloTimeout)
        if (reply is Frame.HelloErr) return reply.message
        if (reply !is Frame.HelloOk) {
            reply?.releasePayload()
            val why = if (reply == null) "no reply within $helloTimeout" else "the server did not answer HELLO"
            throw CannotConnect(why)
        }
        out.println(reply.url)
        out.flush()
        socket.keepAlive(reply.pingInterval)
        session(reply, transport)
        return "lost the connection to ${login.server}; run tun http again"
    } finally {
        withContext(NonCancellable) { transport.close() }
    }
}

/** Sends HELLO and returns the server's one reply, or `null` after [timeout]. EOF throws [CannotConnect]. */
private suspend fun hello(transport: MuxTransport, token: String, timeout: Duration): Frame? =
    withTimeoutOrNull(timeout) {
        transport.sendFrame(Frame.Hello(VERSION, token, null, WINDOW, MAX_FRAME))
        transport.receiveFrame(MAX_FRAME) ?: throw CannotConnect("the server closed the connection")
    }

/** The mux session until the connection ends. Streams the server opens are reset with `cancel` until Story 1.8. */
private suspend fun session(ok: Frame.HelloOk, transport: MuxTransport) = coroutineScope {
    val spec = muxSession {
        role(Role.CLIENT)
        local(WINDOW, MAX_FRAME)
        peer(ok.window, ok.maxFrame)
    }
    spec.start(transport, this) {}.join()
}
