package tun.wire

import io.netty.buffer.ByteBufUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The HTTP/1.1 server and in-place WebSocket upgrade of the Story 1.6 spec. */
class HttpServerTest {
    @Test
    fun `keep-alive - requests on one connection are handled in turn, each with its own Host`() = httpTest({ ex ->
        ex.respond(200, "${ex.method} ${ex.host} ${ex.path}")
    }) { port ->
        RawHttp(port).use { http ->
            assertEquals("GET a.test /x?q=1", http.request("/x?q=1", "a.test")?.body)
            assertEquals("GET b.test /y", http.request("/y", "b.test")?.body)
            assertEquals("GET null /z", http.request("/z", null)?.body)
        }
    }

    @Test
    fun `body - an unread body on a 404 is read and released, and the connection stays usable`() = httpTest({ ex ->
        if (ex.method == "POST") ex.respond(404) else ex.respond(200, "next")
    }) { port ->
        RawHttp(port).use { http ->
            assertEquals(404, http.request("/", "a.test", "POST", Random.nextBytes(100_000))?.status)
            assertEquals("next", http.request("/", "a.test")?.body)
        }
    }

    @Test
    fun `no response - the handler returning without one closes the connection`() = httpTest({}) { port ->
        RawHttp(port).use { http ->
            assertNull(http.request("/", "a.test"))
        }
    }

    @Test
    fun `connection close - honoured after the response`() = httpTest({ it.respond(200, "bye") }) { port ->
        RawHttp(port).use { http ->
            http.send("GET / HTTP/1.1\r\nHost: a\r\nConnection: close\r\n\r\n".toByteArray())
            assertEquals("bye", http.response()?.body)
            assertTrue(http.closedByServer())
        }
    }

    @Test
    fun `not an upgrade - acceptWebSocket returns null and the request can still be answered`() = httpTest({ ex ->
        if (ex.acceptWebSocket(MAX, 1.seconds) == null) ex.respond(426)
    }) { port ->
        RawHttp(port).use { assertEquals(426, it.request("/", "a.test")?.status) }
    }

    @Test
    fun `websocket - continuations are reassembled, and messages go both ways`() = wsTest { ws, received ->
        ws.sendBinary(byteArrayOf(1, 2), last = false)
        ws.sendBinary(byteArrayOf(3), last = false)
        ws.sendBinary(byteArrayOf(4, 5))
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), received.receive())
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), ws.nextMessage())
    }

    @Test
    fun `websocket - a message over maxMessage closes, in one frame or in fragments`() {
        wsTest { ws, received ->
            ws.sendBinary(ByteArray(MAX + 1))
            assertNull(received.receive())
        }
        wsTest { ws, received ->
            ws.sendBinary(ByteArray(MAX), last = false)
            ws.sendBinary(ByteArray(1))
            assertNull(received.receive())
        }
    }

    @Test
    fun `websocket - a text message closes`() = wsTest { ws, received ->
        ws.socket.sendText("hi", true).get(5, TimeUnit.SECONDS)
        assertNull(received.receive())
    }

    @Test
    fun `websocket - pings are answered with pongs`() = wsTest { ws, _ ->
        ws.socket.sendPing(java.nio.ByteBuffer.wrap(byteArrayOf(7))).get(5, TimeUnit.SECONDS)
        assertContentEquals(byteArrayOf(7), ws.pongs.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `websocket - no inbound frame for the idle timeout closes`() = wsTest(idle = 300.milliseconds) { ws, received ->
        assertNull(received.receive())
        ws.awaitClosed()
    }

    /**
     * Runs a server whose WebSocket handler echoes each message and reports it to `received` (`null` at close),
     * then [block] with a connected JDK client.
     */
    private fun wsTest(
        idle: Duration = 5.seconds,
        block: suspend CoroutineScope.(WsClient, Channel<ByteArray?>) -> Unit,
    ) {
        val received = Channel<ByteArray?>(Channel.UNLIMITED)
        httpTest({ ex -> echo(checkNotNull(ex.acceptWebSocket(MAX, idle)), received) }) { port ->
            val ws = WsClient.connect(port, "/ws")
            block(ws, received)
            ws.socket.abort()
        }
    }

    private suspend fun echo(ws: WebSocket, received: Channel<ByteArray?>) {
        while (true) {
            val buf = ws.receive()
            received.send(buf?.let(ByteBufUtil::getBytes))
            ws.send(buf ?: break)
        }
        ws.close()
    }

    // handlers run off the test thread, which blocks in the JDK clients
    private fun httpTest(
        handler: HttpHandler,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        block: suspend CoroutineScope.(Int) -> Unit,
    ) = runBlocking {
        Wire().use { wire ->
            val handlers = CoroutineScope(dispatcher + Job(coroutineContext.job))
            try {
                val server = wire.start(httpServer { handle(handler) }, handlers)
                withTimeout(10.seconds) { block(server.port) }
            } finally {
                handlers.coroutineContext.job.cancelAndJoin()
            }
        }
    }

    private companion object {
        const val MAX = 1024
    }
}
