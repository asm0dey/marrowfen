package tun.app

import java.io.ByteArrayOutputStream
import java.io.PrintStream
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
    fun `stub modes report not implemented on stderr`() {
        assertEquals(1, tun("server"))
        assertEquals(1, tun("http", "8080"))
        assertEquals("not implemented\nnot implemented", err.toString().trim())
        assertEquals("", out.toString())
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
