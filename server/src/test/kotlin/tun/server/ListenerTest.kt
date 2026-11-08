package tun.server

import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import tun.core.Level
import tun.core.Token
import tun.core.sha256Hex
import tun.mux.Frame
import tun.mux.MuxSession
import tun.mux.decode
import tun.mux.encode
import tun.wire.RawHttp
import tun.wire.Wire
import tun.wire.WsClient
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** One test per row of the I/O matrix in the Story 1.6 spec, plus its acceptance criteria. */
class ListenerTest {
    @Test
    fun `health - 200 ok on the apex`() = serverTest { port ->
        val response = RawHttp(port).use { it.request("/_tun/health", APEX) }
        assertEquals(200, response?.status)
        assertEquals("ok", response?.body)
    }

    @Test
    fun `keep-alive mix - every request on one connection is routed on its own Host`() {
        val sessions = java.util.Collections.synchronizedList(mutableListOf<MuxSession>())
        serverTest(onTunnel = { ex, session -> sessions += session; ex.respond(200, "tunnel") }) { port ->
            val (client, ok) = handshake(port)
            RawHttp(port).use { http ->
                assertEquals("ok", http.request("/_tun/health", APEX)?.body)
                assertEquals("tunnel", http.request("/", "${ok.name}.$APEX")?.body)
                assertEquals(404, http.request("/", "other.test")?.status)
                assertEquals("tunnel", http.request("/", "${ok.name.uppercase()}.$APEX:443")?.body)
            }
            assertEquals(2, sessions.size)
            assertTrue(sessions[0] === sessions[1], "both requests reached the one session")
            client.socket.abort()
        }
    }

    @Test
    fun `unknown name, apex path, no Host - 404`() = serverTest { port ->
        RawHttp(port).use { http ->
            assertEquals(404, http.request("/", "zzzzzzzz.$APEX")?.status)
            assertEquals(404, http.request("/x", APEX)?.status)
            assertEquals(404, http.request("/_tun/health", null)?.status)
        }
    }

    @Test
    fun `connect without upgrade - 426`() = serverTest { port ->
        assertEquals(426, RawHttp(port).use { it.request("/_tun/connect", APEX) }?.status)
    }

    @Test
    fun `valid HELLO - exactly one HELLO_OK, and the name is live`() = serverTest { port ->
        val (client, ok) = handshake(port)
        assertTrue(Regex("[a-z2-7]{8}").matches(ok.name), ok.name)
        assertTrue(ok.name != "wanted", "the requested name is ignored")
        assertEquals(Frame.HelloOk(ok.name, "https://${ok.name}.$APEX", 262_144u, 16_384u, PING), ok)
        assertNull(client.messages.poll(SETTLE_MS, TimeUnit.MILLISECONDS), "a second frame after HELLO_OK")
        val response = RawHttp(port).use { it.request("/", "${ok.name}.$APEX") }
        assertEquals(502, response?.status)
        assertEquals("tun: relay not implemented", response?.body)
        client.socket.abort()
    }

    @Test
    fun `unknown token - HELLO_ERR 2 naming the fix, then close, token never echoed`() = serverTest { port ->
        val other = "ab".repeat(32)
        val err = refused(port, hello(token = other))
        assertEquals(Frame.HelloErr(2u, "unknown token; ask the server operator for a token, then run tun login"), err)
        assertFalse(other in err.message || TOKEN in err.message)
        assertEquals(2, refused(port, hello(token = TOKEN.uppercase())).code.toInt())
    }

    @Test
    fun `several defects - the first check in the documented order answers`() = serverTest { port ->
        val unknown = "ab".repeat(32)
        assertEquals(1, refused(port, hello(version = 2u, window = 0u, token = unknown)).code.toInt())
        assertEquals(3, refused(port, hello(window = 0u, token = unknown)).code.toInt())
    }

    @Test
    fun `full-size message after HELLO_OK - accepted, connection stays up`() = serverTest { port ->
        val (client) = handshake(port)
        client.send(Frame.Data(1u, Unpooled.wrappedBuffer(ByteArray(16_384)))) // stream 1 is ignored by the server
        client.socket.sendPing(ByteBuffer.wrap(byteArrayOf(1))).get(TIMEOUT_S, TimeUnit.SECONDS)
        assertEquals(listOf<Byte>(1), client.pongs.poll(TIMEOUT_S, TimeUnit.SECONDS)?.toList())
        client.socket.abort()
    }

    @Test
    fun `bad version, bad HELLO - HELLO_ERR 1 or 3 naming the fix, then close`() = serverTest { port ->
        assertEquals(1, refused(port, hello(version = 2u)).code.toInt())
        listOf(
            hello(window = 0u),
            hello(maxFrame = 16_383u),
            Frame.Data(1u, Unpooled.copiedBuffer(byteArrayOf(1, 2, 3))),
        ).forEach { first ->
            val err = refused(port, first)
            assertEquals(3, err.code.toInt())
            assertContains(err.message, "upgrade tun")
        }
    }

    @Test
    fun `undecodable first message - close, no reply`() = serverTest { port ->
        val client = connect(port)
        client.sendBinary(byteArrayOf(9, 9, 9))
        client.awaitClosed()
        assertNull(client.messages.poll())
    }

    @Test
    fun `no HELLO - close after the HELLO timeout`() = serverTest(helloTimeout = 300.milliseconds) { port ->
        val client = connect(port)
        client.awaitClosed()
        assertNull(client.messages.poll())
    }

    @Test
    fun `ping after HELLO_OK - pong`() = serverTest { port ->
        val (client) = handshake(port)
        client.socket.sendPing(ByteBuffer.wrap(byteArrayOf(4, 2))).get(TIMEOUT_S, TimeUnit.SECONDS)
        assertEquals(listOf<Byte>(4, 2), client.pongs.poll(TIMEOUT_S, TimeUnit.SECONDS)?.toList())
        client.socket.abort()
    }

    @Test
    fun `silent peer - closed after 3 ping intervals, and the name is gone`() =
        serverTest(pingInterval = 200.milliseconds) { port ->
            val (client, ok) = handshake(port)
            Thread.sleep(300) // 1.5 x pingInterval: still alive
            assertFalse(client.closed.isDone, "closed before 3 ping intervals")
            client.awaitClosed()
            awaitNotFound(port, ok.name)
        }

    @Test
    fun `disconnect after HELLO_OK - the name leaves the snapshot`() = serverTest { port ->
        val (client, ok) = handshake(port)
        client.socket.abort()
        awaitNotFound(port, ok.name)
    }

    @Test
    fun `bind fails - exit 1 with the fix on stderr`(@TempDir dir: Path) {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { taken ->
            dir.resolve("tun.toml").writeText("version = 1\n[server]\ndomain = \"$APEX\"\nport = ${taken.localPort}\n")
            val err = ByteArrayOutputStream()
            assertEquals(1, runServer(listOf("--config-dir", dir.toString()), PrintStream(err, true)))
            val line = err.toString().trim()
            assertContains(line, "tun: cannot listen on 127.0.0.1:${taken.localPort} (")
            assertContains(line, "); free the port or change port in tun.toml")
        }
    }

    @Test
    fun `default bind - served on 127 0 0 1 only`(@TempDir dir: Path) {
        val external = NetworkInterface.networkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address }
        assumeTrue(external != null, "no non-loopback IPv4 address to probe")
        dir.resolve("tun.toml").writeText("version = 1\n[server]\ndomain = \"$APEX\"\n")
        val config = loadConfig(dir).copy(port = 0)
        assertEquals("127.0.0.1", config.bind)
        serverTest(config) { port ->
            assertEquals("ok", RawHttp(port).use { it.request("/_tun/health", APEX) }?.body)
            assertFailsWith<java.io.IOException> {
                Socket().use { it.connect(InetSocketAddress(external, port), 1000) }
            }
        }
    }

    // --- helpers

    private fun connect(port: Int) = WsClient.connect(port, "/_tun/connect", APEX)

    private fun hello(
        version: UShort = 1u,
        token: String = TOKEN,
        window: UInt = 65_536u,
        maxFrame: UInt = 16_384u,
    ) = Frame.Hello(version, token, "wanted", window, maxFrame)

    /** Connects and completes the handshake. */
    private fun handshake(port: Int): Pair<WsClient, Frame.HelloOk> {
        val client = connect(port)
        client.send(hello())
        return client to assertIs<Frame.HelloOk>(client.frame())
    }

    /** Sends [first] on a fresh connection and expects exactly one HELLO_ERR, then the close. */
    private fun refused(port: Int, first: Frame): Frame.HelloErr {
        val client = connect(port)
        client.send(first)
        val err = assertIs<Frame.HelloErr>(client.frame())
        client.awaitClosed()
        assertNull(client.messages.poll())
        return err
    }

    private fun WsClient.send(frame: Frame) {
        val buf = encode(frame, ByteBufAllocator.DEFAULT)
        try {
            sendBinary(ByteBufUtil.getBytes(buf))
        } finally {
            buf.release()
        }
    }

    private fun WsClient.frame(): Frame = decode(Unpooled.wrappedBuffer(assertNotNull(nextMessage())), 1u shl 20)

    private fun awaitNotFound(port: Int, name: String) {
        val deadline = System.nanoTime() + TIMEOUT_S * 1_000_000_000
        while (RawHttp(port).use { it.request("/", "$name.$APEX") }?.status != 404) {
            check(System.nanoTime() < deadline) { "$name still routed" }
            Thread.sleep(20)
        }
    }

    private fun serverTest(
        helloTimeout: Duration = 10.seconds,
        pingInterval: Duration = PING,
        onTunnel: TunnelHandler = ::relayPending,
        block: (Int) -> Unit,
    ): Unit = serverTest(config(pingInterval), helloTimeout, onTunnel, block = block)

    private fun serverTest(
        config: ServerConfig,
        helloTimeout: Duration = 10.seconds,
        onTunnel: TunnelHandler = ::relayPending,
        dispatcher: CoroutineDispatcher = Dispatchers.Default, // the server runs off the test thread, which blocks
        block: (Int) -> Unit,
    ): Unit = runBlocking {
        Wire().use { wire ->
            val scope = CoroutineScope(dispatcher + Job(coroutineContext.job))
            try {
                block(startServer(config, wire, scope, helloTimeout, onTunnel).port)
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }
    }

    private fun config(pingInterval: Duration) = ServerConfig(
        domain = APEX,
        bind = "127.0.0.1",
        port = 0,
        grace = 60.seconds,
        pingInterval = pingInterval,
        tokens = listOf(Token("laptop", sha256Hex(TOKEN), Level.RANDOM)),
    )

    private companion object {
        const val APEX = "example.test"
        val TOKEN = "0f".repeat(32)
        val PING = 20.seconds
        const val SETTLE_MS = 300L
        const val TIMEOUT_S = 5L
    }
}
