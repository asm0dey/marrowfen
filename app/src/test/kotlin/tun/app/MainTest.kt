package tun.app

import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class MainTest {
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()

    private fun tun(vararg args: String) = run(args.toList(), PrintStream(out, true), PrintStream(err, true))

    @Test
    fun `version prints the version and exits 0`() {
        assertEquals(0, tun("--version"))
        assertEquals("tun $VERSION", out.toString().trim())
    }

    @Test
    fun `http is a stub that reports not implemented on stderr`() {
        assertEquals(1, tun("http", "8080"))
        assertEquals("not implemented", err.toString().trim())
        assertEquals("", out.toString())
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
    fun `server with a valid config reaches the listener stub`(@TempDir dir: Path) {
        dir.resolve("tun.toml").writeText("version = 1\n[server]\ndomain = \"tun.example.com\"\n")
        assertEquals(1, tun("server", "--config-dir", dir.toString()))
        assertEquals("tun: listener not implemented", err.toString().trim())
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
            listOf("server", "http", "--version").forEach { assertContains(err.toString(), it) }
        }
    }
}
