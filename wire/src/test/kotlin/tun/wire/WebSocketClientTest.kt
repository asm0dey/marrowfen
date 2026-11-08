package tun.wire

import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WebSocketClientTest {
    @Test
    fun `round trip with the wire server`() = withServer({ ws ->
        while (true) ws.send(ws.receive() ?: break) // echo
    }) { wire, port ->
        val ws = wire.connectWebSocket(URI("ws://127.0.0.1:$port/_tun/connect"), MAX)
        ws.send(Unpooled.copiedBuffer(byteArrayOf(1, 2, 3)))
        val echoed = assertNotNullAndRelease(ws.receive())
        assertEquals(listOf<Byte>(1, 2, 3), echoed.toList())
        ws.close()
    }

    @Test
    fun `non-101 - WebSocketConnectException`() = withServer(accept = false) { wire, port ->
        val e = assertFailsWith<WebSocketConnectException> {
            wire.connectWebSocket(URI("ws://127.0.0.1:$port/"), MAX)
        }
        assertContains(e.message.orEmpty(), "404")
    }

    @Test
    fun `refused - WebSocketConnectException`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        Wire().use { wire ->
            assertFailsWith<WebSocketConnectException> { wire.connectWebSocket(URI("ws://127.0.0.1:$port/"), MAX) }
        }
        Unit
    }

    @Test
    fun `keepalive pings keep a 3 x idle server open`() {
        val ended = CompletableDeferred<Unit>()
        withServer({ ws ->
            while (ws.receive()?.release() != null) Unit // reading lets the server pong
            ended.complete(Unit)
        }, idle = 300.milliseconds) { wire, port ->
            val ws = wire.connectWebSocket(URI("ws://127.0.0.1:$port/"), MAX)
            ws.keepAlive(100.milliseconds)
            val reading = async { ws.receive() } // reading lets pongs in
            delay(1.seconds)
            assertFalse(ended.isCompleted, "the server closed a pinging client")
            assertFalse(reading.isCompleted, "the client closed although the server pongs")
            ws.close()
            assertNull(reading.await())
        }
    }

    @Test
    fun `silent peer closes the client after 3 intervals`() = withServer({ awaitCancellation() }) { wire, port ->
        val ws = wire.connectWebSocket(URI("ws://127.0.0.1:$port/"), MAX)
        ws.keepAlive(100.milliseconds)
        val started = System.nanoTime()
        assertNull(withTimeout(5.seconds) { ws.receive() })
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assert(elapsed >= 250) { "closed after $elapsed ms, before 3 intervals" }
    }

    @Test
    fun `keepAlive on a connection the server already closed - nothing installed, no exception`() =
        withServer({}) { wire, port -> // the handler returns at once, which closes the connection
            val ws = wire.connectWebSocket(URI("ws://127.0.0.1:$port/"), MAX)
            assertNull(withTimeout(5.seconds) { ws.receive() })
            delay(200) // lets Netty deregister the channel and empty its pipeline
            ws.keepAlive(100.milliseconds)
        }

    @Test
    fun `a message over maxMessage in one frame - the client closes`() =
        assertClientCloses(frame(0x82, ByteArray(MAX + 1)))

    @Test
    fun `a message over maxMessage in fragments - the client closes`() =
        assertClientCloses(frame(0x02, ByteArray(10_000)) + frame(0x80, ByteArray(MAX + 1 - 10_000)))

    @Test
    fun `a text message - the client closes`() = assertClientCloses(frame(0x81, "hi".toByteArray()))

    // --- helpers

    /** A raw server that answers the upgrade with 101, then writes [frames]; the client must see only the close. */
    private fun assertClientCloses(frames: ByteArray) = runBlocking {
        ServerSocket(0).use { server ->
            val peer = Thread { server.accept().use { serveRaw(it, frames) } }.apply { start() }
            Wire().use { wire ->
                val ws = wire.connectWebSocket(URI("ws://127.0.0.1:${server.localPort}/"), MAX)
                assertNull(withTimeout(5.seconds) { ws.receive() })
            }
            peer.join(5000)
        }
    }

    private fun serveRaw(socket: java.net.Socket, frames: ByteArray) {
        val head = StringBuilder()
        val input = socket.getInputStream()
        while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
        val key = Regex("(?i)sec-websocket-key: *(\\S+)").find(head)!!.groupValues[1]
        val sha1 = java.security.MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
        val accept = java.util.Base64.getEncoder().encodeToString(sha1)
        val response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n"
        socket.getOutputStream().apply { write(response.toByteArray() + frames) }.flush()
        runCatching { while (input.read() != -1) Unit } // until the client closes
    }

    /** One unmasked server frame: [first] is FIN + opcode. */
    private fun frame(first: Int, payload: ByteArray): ByteArray {
        val length = if (payload.size < 126) {
            byteArrayOf(payload.size.toByte())
        } else {
            byteArrayOf(126, (payload.size shr 8).toByte(), payload.size.toByte())
        }
        return byteArrayOf(first.toByte()) + length + payload
    }

    private fun assertNotNullAndRelease(buf: io.netty.buffer.ByteBuf?): ByteArray {
        kotlin.test.assertNotNull(buf)
        return ByteBufUtil.getBytes(buf).also { buf.release() }
    }

    /** A wire HTTP server that upgrades every request (or answers 404 when not [accept]) and runs [onSocket]. */
    private fun withServer(
        onSocket: suspend (WebSocket) -> Unit = {},
        idle: Duration = 30.seconds,
        accept: Boolean = true,
        dispatcher: CoroutineDispatcher = Dispatchers.Default, // the server runs off the test thread
        block: suspend CoroutineScope.(Wire, Int) -> Unit,
    ): Unit = runBlocking {
        Wire().use { wire ->
            val scope = CoroutineScope(dispatcher + Job(coroutineContext.job))
            val spec = httpServer {
                handle { ex ->
                    if (!accept) return@handle ex.respond(404, "nope")
                    ex.acceptWebSocket(MAX, idle)?.let { onSocket(it) }
                }
            }
            try {
                block(wire, wire.start(spec, scope).port)
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }
    }

    private companion object {
        const val MAX = 16_390
    }
}
