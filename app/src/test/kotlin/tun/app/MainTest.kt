package tun.app

import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class MainTest {
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()

    @TempDir
    lateinit var home: Path

    private fun tun(vararg args: String) =
        run(args.toList(), PrintStream(out, true), PrintStream(err, true), home.resolve("config.toml"))

    @Test
    fun `version prints the version and exits 0`() {
        assertEquals(0, tun("--version"))
        assertEquals("tun $VERSION", out.toString().trim())
    }

    @Test
    fun `http without a login names the fix and exits 1`() {
        assertEquals(1, tun("http", "8080"))
        assertEquals("tun: not logged in; run tun login <server-url> <token>", err.toString().trim())
        assertEquals("", out.toString())
    }

    @Test
    fun `login with the wrong argument count prints its usage and exits 2`() {
        assertEquals(2, tun("login", "wss://tun.example.com"))
        assertEquals("usage: tun login <server-url> <token>", err.toString().trim())
    }

    @Test
    fun `server without a config names the missing file and exits 1`(@TempDir dir: Path) {
        assertEquals(1, tun("server", "--config-dir", dir.toString()))
        assertEquals("tun: ${dir.resolve("tun.toml")} not found; create it (see the README)", err.toString().trim())
        assertEquals("", out.toString())
    }

    @Test
    fun `server accepts a state dir`(@TempDir dir: Path) {
        assertEquals(1, tun("server", "--config-dir", dir.toString(), "--state-dir", "x"))
        assertEquals("tun: ${dir.resolve("tun.toml")} not found; create it (see the README)", err.toString().trim())
    }

    @Test
    fun `server with a valid config reaches the listener, and a taken port exits 1`(@TempDir dir: Path) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { taken ->
            val port = taken.localPort
            dir.resolve("tun.toml").writeText("version = 1\n[server]\ndomain = \"tun.example.com\"\nport = $port\n")
            assertEquals(1, tun("server", "--config-dir", dir.toString()))
            assertContains(err.toString().trim(), "tun: cannot listen on 127.0.0.1:$port (")
        }
    }

    @Test
    fun `server with an unknown flag prints its usage and exits 2`() {
        assertEquals(2, tun("server", "--bogus", "x"))
        assertContains(err.toString(), "--config-dir")
    }

    @Test
    fun `bad usage names the valid modes and exits 2`() {
        for (args in listOf(emptyArray(), arrayOf("bogus"))) {
            err.reset()
            assertEquals(2, tun(*args))
            listOf("server", "login", "http", "--version").forEach { assertContains(err.toString(), it) }
        }
    }
}
