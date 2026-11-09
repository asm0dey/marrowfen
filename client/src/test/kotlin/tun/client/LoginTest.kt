package tun.client

import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The login rows of the I/O matrix in the Story 1.7 spec. */
class LoginTest {
    @TempDir
    lateinit var home: Path

    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()
    private val file get() = home.resolve(".config/tun/config.toml")

    private fun login(vararg args: String) =
        runLogin(args.toList(), PrintStream(out, true), PrintStream(err, true), file)

    @Test
    fun `login wss - saved 0600 in a 0700 directory, trailing slash dropped`() {
        assertEquals(0, login("wss://tun.example.com/", TOKEN))
        assertEquals("saved wss://tun.example.com to $file", out.toString().trim())
        assertEquals("", err.toString())
        assertPrivate(file)
        val saved = loadLogin(file)
        assertEquals("wss://tun.example.com", saved?.server)
        assertEquals(TOKEN, saved?.token)
        assertEquals(listOf(file), file.parent.listDirectoryEntries(), "no temp file left behind")
        assertFalse(TOKEN in out.toString())
    }

    @Test
    fun `login local - ws localhost with a port is saved`() {
        assertEquals(0, login("ws://localhost:8000", TOKEN))
        assertEquals("ws://localhost:8000", loadLogin(file)?.server)
    }

    @Test
    fun `login in upper case - scheme and host are saved lower case`() {
        assertEquals(0, login("WSS://Tun.Example.com", TOKEN))
        assertEquals("wss://tun.example.com", loadLogin(file)?.server)
        assertEquals(0, login("ws://LOCALHOST:8000", TOKEN))
        assertEquals("ws://localhost:8000", loadLogin(file)?.server)
    }

    @Test
    fun `login again - the file is replaced and stays 0600`() {
        login("wss://one.example.com", TOKEN)
        assertEquals(0, login("wss://two.example.com:8443", "other"))
        assertEquals("wss://two.example.com:8443", loadLogin(file)?.server)
        assertEquals("other", loadLogin(file)?.token)
        assertPrivate(file)
    }

    @Test
    fun `login bad URL - exit 1 naming the fix, nothing written`() {
        val bad = listOf(
            "ws://example.com", "http://x", "wss://x/path", "nonsense", "wss://x?q", "wss://u@x", "wss://x#f",
            "wss://x:99999", "ws://localhost/p",
        )
        for (url in bad) {
            err.reset()
            assertEquals(1, login(url, TOKEN), url)
            val line = "tun: $url is not a wss:// URL; use wss://<host> (ws:// only for localhost)"
            assertEquals(line, err.toString().trim())
            assertFalse(file.parent.exists(), "$url wrote something")
        }
        assertFalse(TOKEN in err.toString())
    }

    @Test
    fun `login usage - wrong argument count exits 2`() {
        for (args in listOf(emptyArray(), arrayOf("wss://x"), arrayOf("wss://x", "t", "extra"))) {
            err.reset()
            assertEquals(2, login(*args))
            assertEquals("usage: tun login <server-url> <token>", err.toString().trim())
        }
    }

    @Test
    fun `no file or a broken file - no login`() {
        assertNull(loadLogin(file))
        Files.createDirectories(file.parent)
        Files.writeString(file, "server = \"wss://x\"\n")
        assertNull(loadLogin(file))
        Files.writeString(file, "server = \"ws://example.com\"\ntoken = \"t\"\n") // decodes, but not a server URL
        assertNull(loadLogin(file))
    }

    @Test
    fun `config path - per OS`() {
        val home = mapOf("HOME" to "/home/me")
        assertEquals(Path.of("/home/me/.config/tun/config.toml"), configFileFor("Linux", home, "/pw/me"))
        val xdg = this.home.resolve("xdg").toString() // absolute on every OS; "/xdg" is relative on Windows
        assertEquals(
            Path.of(xdg, "tun", "config.toml"),
            configFileFor("Linux", home + ("XDG_CONFIG_HOME" to xdg), "/pw/me"),
        )
        assertEquals( // a relative XDG_CONFIG_HOME is ignored, as is an empty HOME
            Path.of("/pw/me/.config/tun/config.toml"),
            configFileFor("Linux", mapOf("HOME" to "", "XDG_CONFIG_HOME" to "rel"), "/pw/me"),
        )
        assertEquals(
            Path.of("/Users/me/Library/Application Support/tun/config.toml"),
            configFileFor("Mac OS X", mapOf("HOME" to "/Users/me"), "/pw/me"),
        )
        val appData = "C:\\Users\\me\\AppData\\Roaming"
        assertEquals(
            Path.of(appData, "tun", "config.toml"),
            configFileFor("Windows 11", mapOf("APPDATA" to appData), "C:\\Users\\me"),
        )
    }

    @Test
    fun `a write the file system refuses - the cannot-write line`() {
        Files.writeString(home.resolve("blocker"), "a file where the directory should go")
        val blocked = home.resolve("blocker/tun/config.toml")
        val code = runLogin(listOf("wss://x", TOKEN), PrintStream(out, true), PrintStream(err, true), blocked)
        assertEquals(1, code)
        val line = err.toString().trim()
        assertTrue(line.startsWith("tun: cannot write $blocked ("), line)
        assertTrue(line.endsWith("); check its permissions"), line)
        assertFalse(TOKEN in line)
    }

    @Test
    fun `a file system with neither POSIX permissions nor ACLs - the cannot-write line`() {
        FileSystems.newFileSystem(home.resolve("fs.zip"), mapOf("create" to "true")).use { zip ->
            val target = zip.getPath("/tun/config.toml")
            val code = runLogin(listOf("wss://x", TOKEN), PrintStream(out, true), PrintStream(err, true), target)
            assertEquals(1, code)
            val line = err.toString().trim()
            assertTrue(line.startsWith("tun: cannot write /tun/config.toml ("), line)
            assertTrue(line.endsWith("); check its permissions"), line)
        }
    }

    /** Private as the file system can say it: 0600 in a 0700 directory, else one ALLOW entry for the user alone. */
    private fun assertPrivate(file: Path) {
        val views = file.fileSystem.supportedFileAttributeViews()
        when {
            "posix" in views -> {
                assertEquals("rw-------", mode(file))
                assertEquals("rwx------", mode(file.parent))
            }
            "acl" in views -> assertOwnerAcl(file)
            else -> fail("neither posix nor acl in $views")
        }
    }

    private fun mode(path: Path) = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    private fun assertOwnerAcl(file: Path) {
        val acl = Files.getFileAttributeView(file, AclFileAttributeView::class.java).acl
        val user = file.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
        val entry = acl.singleOrNull() ?: fail("expected one ACL entry, got $acl") // an inherited entry is a second one
        assertEquals(AclEntryType.ALLOW, entry.type())
        assertEquals(user, entry.principal())
        assertEquals(emptySet(), entry.flags(), "inheritance flags")
    }

    private companion object {
        val TOKEN = "0f".repeat(32)
    }
}
