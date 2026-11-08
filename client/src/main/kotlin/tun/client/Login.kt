package tun.client

import com.akuleshov7.ktoml.Toml
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.io.PrintStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path
import kotlin.io.path.readText

/** What `tun login` saved. [token] is a secret: never print it. */
class Login(val server: String, val token: String)

@Serializable
private class ConfigToml(val server: String, val token: String)

private const val USAGE = "usage: tun login <server-url> <token>"
private const val MAX_PORT = 65535
private val toml = Toml()

/** `config.toml` in this OS's user config directory; see [configFileFor]. */
fun defaultConfigFile(): Path =
    configFileFor(System.getProperty("os.name"), System.getenv(), System.getProperty("user.home"))

/**
 * `config.toml` in the user config directory for [os] (an `os.name`): Linux `$XDG_CONFIG_HOME/tun` when set and
 * absolute, else `$HOME/.config/tun`; macOS `$HOME/Library/Application Support/tun`; Windows `%APPDATA%\tun`.
 * `$HOME` comes before [userHome]: on Linux the JDK takes `user.home` from the password database, not `$HOME`.
 */
internal fun configFileFor(os: String, env: Map<String, String>, userHome: String): Path {
    fun set(name: String) = env[name]?.takeIf { it.isNotEmpty() }
    val home = set("HOME") ?: userHome
    val dir = when {
        os.startsWith("Windows") -> Path.of(set("APPDATA") ?: Path.of(userHome, "AppData", "Roaming").toString())
        os.startsWith("Mac") -> Path.of(home, "Library", "Application Support")
        else -> set("XDG_CONFIG_HOME")?.let(Path::of)?.takeIf { it.isAbsolute } ?: Path.of(home, ".config")
    }
    return dir.resolve("tun").resolve("config.toml")
}

/** The `login` mode: validates the URL, then saves it and [args]' token to [configFile]. Never contacts the server. */
fun runLogin(args: List<String>, out: PrintStream, err: PrintStream, configFile: Path): Int {
    if (args.size != 2) return 2.also { err.println(USAGE) }
    val (url, token) = args
    val server = serverOf(url)
    val failure = if (server == null) {
        "$url is not a wss:// URL; use wss://<host> (ws:// only for localhost)"
    } else {
        save(configFile, server, token, out)
    }
    failure?.let { err.println("tun: $it") }
    return if (failure == null) 0 else 1
}

/** Saves the login and reports it on [out]; returns the problem instead when it cannot be written. */
private fun save(configFile: Path, server: String, token: String, out: PrintStream): String? = try {
    writePrivate(configFile, toml.encodeToString(ConfigToml.serializer(), ConfigToml(server, token)))
    out.println("saved $server to $configFile")
    null
} catch (e: IOException) { // the message names a path, never the token
    cannotWrite(configFile, e)
} catch (e: UnsupportedOperationException) { // a file system with neither POSIX permissions nor ACLs
    cannotWrite(configFile, e)
}

private fun cannotWrite(configFile: Path, e: Exception) =
    "cannot write $configFile (${e.message ?: e.javaClass.simpleName}); check its permissions"

/** The saved login, or `null` when there is none or it cannot be read; either way `tun login` fixes it. */
fun loadLogin(configFile: Path): Login? {
    val saved = try {
        decode(configFile.readText())
    } catch (_: IOException) { // a missing file too
        null
    } catch (_: SerializationException) {
        null
    }
    return saved?.let { s -> serverOf(s.server)?.let { Login(it, s.token) } }
}

private fun decode(text: String): ConfigToml = toml.decodeFromString(ConfigToml.serializer(), text)

/**
 * `wss://<host>[:port]`, or `ws://localhost[:port]`, with one trailing `/` dropped; `null` for anything else,
 * including a path, query, fragment or user info.
 */
internal fun serverOf(url: String): String? {
    val uri = try {
        URI(url)
    } catch (_: URISyntaxException) {
        return null
    }
    val bare = uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        uri.rawPath.orEmpty() in setOf("", "/") && uri.port <= MAX_PORT && uri.port != 0
    val scheme = uri.scheme?.lowercase()
    val host = uri.host?.lowercase()
    val allowed = scheme == "wss" || (scheme == "ws" && host == "localhost")
    return if (bare && allowed) "$scheme://$host" + (if (uri.port == -1) "" else ":${uri.port}") else null
}
