package tun.server

import org.junit.jupiter.api.io.TempDir
import tun.core.Level
import tun.core.Token
import tun.core.sha256Hex
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

/** One test per config row of the I/O matrix in the Story 1.5 spec. */
class ServerConfigTest {
    @TempDir
    lateinit var dir: Path

    private val hashA = sha256Hex("token-a")
    private val hashB = sha256Hex("token-b")

    private fun load(text: String): ServerConfig {
        dir.resolve("tun.toml").writeText(text.trimIndent())
        return loadConfig(dir)
    }

    private fun invalid(text: String): String = assertFailsWith<ConfigException> { load(text) }.message.orEmpty()

    private fun valid(server: String = "domain = \"tun.example.com\"", tokens: String = "") = """
        version = 1
        [server]
        $server
        $tokens
    """

    private fun token(alias: String, hash: String, level: String = "random") =
        "[[token]]\nalias = \"$alias\"\nhash = \"sha256:$hash\"\nlevel = \"$level\"\n"

    @Test
    fun `valid file - loads with defaults and ignores unknown keys and tables`() {
        val config = load(
            """
            version = 1
            future = "ignored"
            [server]
            domain = "tun.example.com"
            [extra]
            key = 1
            [[token]]
            alias = "laptop"
            hash = "sha256:$hashA"
            level = "random"
            created = "2026-11-08"
            [[token]]
            alias = "ci"
            hash = "sha256:${hashB.uppercase()}"
            level = "named"
            """,
        )
        val tokens = listOf(Token("laptop", hashA, Level.RANDOM), Token("ci", hashB, Level.NAMED))
        assertEquals(ServerConfig("tun.example.com", "127.0.0.1", 8000, 60.seconds, 20.seconds, tokens), config)
    }

    @Test
    fun `valid file - explicit values and an empty token list`() {
        val config = load(
            valid("domain = \"t.io\"\nbind = \"0.0.0.0\"\nport = 9000\ngrace = \"1m\"\nping_interval = \"5s\""),
        )
        assertEquals(ServerConfig("t.io", "0.0.0.0", 9000, 60.seconds, 5.seconds, emptyList()), config)
    }

    @Test
    fun `missing file - names the path and the fix`() {
        val message = assertFailsWith<ConfigException> { loadConfig(dir) }.message
        assertEquals("${dir.resolve("tun.toml")} not found; create it (see the README)", message)
    }

    @Test
    fun `unreadable - tun toml is a directory`() {
        dir.resolve("tun.toml").createDirectory()
        val message = assertFailsWith<ConfigException> { loadConfig(dir) }.message.orEmpty()
        assertContains(message, "${dir.resolve("tun.toml")} cannot be read")
        assertContains(message, "fix its permissions or contents")
    }

    @Test
    fun `invalid - domain not a bare host name`() {
        for (domain in listOf("https://tun.example.com", "tun.example.com/", "tun.example.com:443", "a b", "")) {
            assertContains(invalid(valid("domain = \"$domain\"")), "set it to a bare host name such as")
        }
    }

    @Test
    fun `invalid - version missing or other`() {
        assertContains(invalid("[server]\ndomain = \"t.io\""), "version is missing; add it")
        assertContains(invalid(valid().replace("version = 1", "version = 2")), "set version = 1")
    }

    @Test
    fun `invalid - domain missing`() {
        assertContains(invalid(valid(server = "port = 8000")), "[server] domain is missing; add it")
    }

    @Test
    fun `invalid - bad or non-positive duration`() {
        assertContains(invalid(valid("domain = \"t.io\"\ngrace = \"soon\"")), "Line 4: \"soon\" is not a positive")
        assertContains(invalid(valid("domain = \"t.io\"\nping_interval = \"0s\"")), "write it like \"60s\"")
        assertContains(invalid(valid("domain = \"t.io\"\ngrace = \"Infinity\"")), "\"Infinity\" is not a positive")
        val literal = invalid(valid("domain = \"t.io\"\ngrace = 'soon'"))
        assertContains(literal, ": \"soon\" is not a positive")
        assertFalse("Line" in literal, literal)
    }

    @Test
    fun `invalid - duplicate alias`() {
        assertContains(invalid(valid(tokens = token("a", hashA) + token("a", hashB))), "alias \"a\" is used twice")
    }

    @Test
    fun `invalid - duplicate hash`() {
        assertContains(invalid(valid(tokens = token("a", hashA) + token("b", hashA))), "share one hash")
    }

    @Test
    fun `invalid - hash not sha256 and 64 hex`() {
        assertContains(invalid(valid(tokens = token("a", "abc"))), "64 hex chars")
        assertContains(invalid(valid(tokens = token("a", hashA).replace("sha256:", "md5:"))), "\"sha256:\"")
    }

    @Test
    fun `invalid - token key missing`() {
        val noHash = "[[token]]\nalias = \"a\"\nlevel = \"random\"\n"
        assertContains(invalid(valid(tokens = noHash)), "[[token]] hash is missing")
    }

    @Test
    fun `invalid - level not random or named`() {
        assertContains(invalid(valid(tokens = token("a", hashA, "admin"))), "\"random\" or \"named\"")
    }

    @Test
    fun `invalid - TOML syntax error names the line`() {
        assertContains(invalid("version = 1\n[server]\ndomain = \"t.io\nport = 1"), "Line 3: ")
    }
}
