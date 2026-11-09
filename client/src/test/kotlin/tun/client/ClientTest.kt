package tun.client

import io.netty.buffer.Unpooled
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import tun.mux.Frame
import tun.mux.MuxTransport
import tun.mux.asMuxTransport
import tun.mux.receiveFrame
import tun.mux.releasePayload
import tun.mux.sendFrame
import tun.wire.Wire
import tun.wire.httpServer
import tun.wire.start
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.ServerSocket
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.io.path.inputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The `tun http` rows of the I/O matrix in the Story 1.7 spec, against a fake server, plus the TLS criterion. */
class ClientTest {
    @TempDir
    lateinit var home: Path

    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()
    private val file get() = home.resolve("config.toml")

    @Test
    fun `http bad port - exit 2 with usage`() {
        login("ws://localhost:1")
        for (args in listOf(listOf("0"), listOf("abc"), emptyList(), listOf("65536"), listOf("1", "2"))) {
            err.reset()
            assertEquals(2, http(args), "$args")
            assertEquals("usage: tun http <port>", err.toString().trim())
        }
    }

    @Test
    fun `http no login - exit 1 naming tun login`() {
        assertEquals(1, http(listOf("8080")))
        assertEquals("tun: not logged in; run tun login <server-url> <token>", err.toString().trim())
    }

    @Test
    fun `connected - HELLO with the token, URL alone on stdout, kept alive past 3 intervals while the server pongs`() {
        val hello = CompletableDeferred<Frame>()
        fakeServer({ transport ->
            hello.complete(assertIsNotNull(transport.receiveFrame(MAX_FRAME)))
            transport.sendFrame(Frame.HelloOk("abcdefgh", URL, 262_144u, MAX_FRAME, 100.milliseconds))
            while (true) transport.receiveFrame(MAX_FRAME)?.releasePayload() ?: break // reading lets the server pong
        }) { port, stop ->
            login("ws://localhost:$port")
            val exit = async { http(listOf("8080")) }
            awaitOut(URL + System.lineSeparator()) // println writes \r\n on Windows
            assertEquals(Frame.Hello(1u, TOKEN, null, 262_144u, MAX_FRAME), runBlocking { hello.await() })
            Thread.sleep(600) // 6 ping intervals
            assertFalse(exit.isDone, "the client quit although the server pongs")
            stop()
            assertEquals(1, exit.get(TIMEOUT_S, TimeUnit.SECONDS))
            assertEquals("tun: lost the connection to ws://localhost:$port; run tun http again", err.toString().trim())
            assertEquals(URL + System.lineSeparator(), out.toString())
        }
    }

    @Test
    fun `HELLO_ERR - exit 1 with the server message, token not in output`() = fakeServer({ transport ->
        transport.receiveFrame(MAX_FRAME)
        transport.sendFrame(Frame.HelloErr(2u, REFUSAL))
    }) { port, _ ->
        login("ws://localhost:$port")
        assertEquals(1, http(listOf("8080")))
        assertEquals("tun: $REFUSAL", err.toString().trim())
        assertEquals("", out.toString())
        assertFalse(TOKEN in err.toString())
    }

    @Test
    fun `cannot connect - refused`() {
        val port = ServerSocket(0).use { it.localPort }
        login("ws://localhost:$port")
        assertEquals(1, http(listOf("8080")))
        assertCannotConnect("ws://localhost:$port")
    }

    @Test
    fun `cannot connect - DNS`() {
        login("wss://no-such-host.invalid")
        assertEquals(1, http(listOf("8080")))
        assertCannotConnect("wss://no-such-host.invalid")
    }

    @Test
    fun `cannot connect - non-101`() = fakeServer(accept = false) { port, _ ->
        login("ws://localhost:$port")
        assertEquals(1, http(listOf("8080")))
        assertCannotConnect("ws://localhost:$port")
        assertContains(err.toString(), "404")
    }

    @Test
    fun `cannot connect - no reply to HELLO within the timeout`() = fakeServer({ awaitCancellation() }) { port, _ ->
        login("ws://localhost:$port")
        assertEquals(1, http(listOf("8080"), helloTimeout = 300.milliseconds))
        assertCannotConnect("ws://localhost:$port")
        assertContains(err.toString(), "no reply within 300ms")
    }

    @Test
    fun `cannot connect - the server closes after HELLO`() = fakeServer({ it.receiveFrame(MAX_FRAME) }) { port, _ ->
        login("ws://localhost:$port")
        assertEquals(1, http(listOf("8080")))
        assertCannotConnect("ws://localhost:$port")
        assertContains(err.toString(), "(the server closed the connection)")
    }

    @Test
    fun `cannot connect - HELLO answered with a DATA frame`() = fakeServer({ transport ->
        transport.receiveFrame(MAX_FRAME)
        transport.sendFrame(Frame.Data(1u, Unpooled.copiedBuffer(byteArrayOf(1, 2, 3))))
        awaitCancellation()
    }) { port, _ ->
        login("ws://localhost:$port")
        assertEquals(1, http(listOf("8080")))
        assertCannotConnect("ws://localhost:$port")
        assertContains(err.toString(), "(the server did not answer HELLO)")
    }

    @Test
    fun `server closes right after HELLO_OK - the lost-connection line, no stack trace`() = fakeServer({ transport ->
        transport.receiveFrame(MAX_FRAME)
        transport.sendFrame(Frame.HelloOk("abcdefgh", URL, 262_144u, MAX_FRAME, 100.milliseconds))
    }) { port, _ ->
        login("ws://localhost:$port")
        assertEquals(1, http(listOf("8080")))
        assertEquals("tun: lost the connection to ws://localhost:$port; run tun http again", err.toString().trim())
    }

    @Test
    fun `dead server - the client closes within about 3 ping intervals`() = fakeServer({ transport ->
        transport.receiveFrame(MAX_FRAME)
        transport.sendFrame(Frame.HelloOk("abcdefgh", URL, 262_144u, MAX_FRAME, 100.milliseconds))
        awaitCancellation() // never receives again, so no pong goes out
    }) { port, _ ->
        login("ws://localhost:$port")
        val started = System.nanoTime()
        assertEquals(1, http(listOf("8080")))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 3000, "took $elapsedMs ms")
        assertEquals("tun: lost the connection to ws://localhost:$port; run tun http again", err.toString().trim())
    }

    @Test
    fun `untrusted certificate - cannot connect, and no HELLO is sent`() {
        val (tls) = tlsServer("localhost")
        tls.use { server ->
            val received = CompletableFuture.supplyAsync {
                server.accept().use { socket -> runCatching { socket.getInputStream().read() } }
            }
            login("wss://localhost:${server.localPort}")
            assertEquals(1, http(listOf("8080")))
            assertCannotConnect("wss://localhost:${server.localPort}")
            assertTrue(received.get(TIMEOUT_S, TimeUnit.SECONDS).isFailure, "the server read application bytes")
        }
    }

    @Test
    fun `trusted certificate for another name - cannot connect on the name, and no HELLO is sent`() {
        val (tls, keys) = tlsServer("other.example")
        val saved = SSLContext.getDefault()
        val trustAll = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        SSLContext.setDefault(SSLContext.getInstance("TLS").apply { init(null, trustAll.trustManagers, null) })
        try {
            tls.use { server ->
                val received = CompletableFuture.supplyAsync {
                    server.accept().use { socket -> runCatching { socket.getInputStream().read() } }
                }
                login("wss://localhost:${server.localPort}")
                assertEquals(1, http(listOf("8080")))
                assertCannotConnect("wss://localhost:${server.localPort}")
                assertContains(err.toString(), "No subject alternative DNS name matching localhost")
                assertTrue(received.get(TIMEOUT_S, TimeUnit.SECONDS).isFailure, "the server read application bytes")
            }
        } finally {
            SSLContext.setDefault(saved)
        }
    }

    // --- helpers

    private fun login(server: String) =
        assertEquals(0, runLogin(listOf(server, TOKEN), PrintStream(ByteArrayOutputStream()), System.err, file))

    private fun http(args: List<String>, helloTimeout: Duration = 10.seconds) =
        runHttp(args, PrintStream(out, true), PrintStream(err, true), file, helloTimeout)

    private fun <T> async(block: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(block)

    private fun assertCannotConnect(server: String) {
        val line = err.toString().trim()
        assertTrue(line.startsWith("tun: cannot connect to $server ("), line)
        assertTrue(line.endsWith("); check the server URL, or run tun login again"), line)
        assertFalse(TOKEN in line)
        assertEquals("", out.toString())
    }

    private fun awaitOut(expected: String) {
        val deadline = System.nanoTime() + TIMEOUT_S * 1_000_000_000
        while (out.toString() != expected) {
            check(System.nanoTime() < deadline) { "stdout is '$out', err '$err'" }
            Thread.sleep(10)
        }
    }

    private fun <T : Any> assertIsNotNull(value: T?): T = kotlin.test.assertNotNull(value)

    /**
     * A fake tun server: wire `httpServer` + `acceptWebSocket`, running [onConnect] on each control connection, or
     * answering 404 when not [accept]. [block] gets its port and a function that stops it.
     */
    private fun fakeServer(
        onConnect: suspend (MuxTransport) -> Unit = {},
        accept: Boolean = true,
        dispatcher: CoroutineDispatcher = Dispatchers.Default, // the server runs off the test thread, which blocks
        block: (Int, () -> Unit) -> Unit,
    ): Unit = runBlocking {
        Wire().use { wire ->
            val scope = CoroutineScope(dispatcher + Job(coroutineContext.job))
            val spec = httpServer {
                handle { ex ->
                    if (!accept || ex.path != "/_tun/connect") return@handle ex.respond(404, "nope")
                    ex.acceptWebSocket(MAX_FRAME.toInt() + 6, 30.seconds)?.let { onConnect(it.asMuxTransport()) }
                }
            }
            val stop = { runBlocking { withTimeout(5.seconds) { scope.coroutineContext.job.cancelAndJoin() } } }
            try {
                block(wire.start(spec, scope).port, stop)
            } finally {
                stop()
            }
        }
    }

    /** A TLS server with a self-signed certificate for [name], which no default trust store holds, and its keys. */
    private fun tlsServer(name: String): Pair<ServerSocket, KeyStore> {
        val store = home.resolve("tls.p12")
        val exe = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool$exe").toString()
        val made = ProcessBuilder(
            keytool, "-genkeypair", "-keyalg", "EC", "-alias", "tun", "-dname", "CN=$name",
            "-ext", "san=dns:$name", "-validity", "1", "-storetype", "PKCS12",
            "-keystore", store.toString(), "-storepass", PASS, "-keypass", PASS,
        ).redirectErrorStream(true).start()
        assertEquals(0, made.waitFor(), made.inputStream.readAllBytes().decodeToString())
        val keys = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, PASS.toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keys, PASS.toCharArray())
        }
        val context = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        return context.serverSocketFactory.createServerSocket(0) to keys
    }

    private companion object {
        val TOKEN = "0f".repeat(32)
        const val URL = "https://abcdefgh.localhost"
        const val MAX_FRAME = 16_384u
        const val TIMEOUT_S = 10L
        const val PASS = "changeit"
        const val REFUSAL = "unknown token; ask the server operator for a token, then run tun login"
    }
}
