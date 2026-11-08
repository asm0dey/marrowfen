package tun.server

import tun.core.Level
import tun.core.Token
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A startup config problem. The message names the problem and the fix, and never holds a token. */
class ConfigException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The validated `[server]` table and tokens of `tun.toml`. */
data class ServerConfig(
    val domain: String,
    val bind: String,
    val port: Int,
    val grace: Duration,
    val pingInterval: Duration,
    val tokens: List<Token>,
)

private const val FILE_NAME = "tun.toml"
private const val DEFAULT_PORT = 8000
private val DEFAULT_GRACE = 60.seconds
private val DEFAULT_PING_INTERVAL = 20.seconds
private val HASH = Regex("sha256:[0-9a-fA-F]{64}")
private val PORTS = 1..65535
private val HOST = Regex("[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*")

/** Loads and validates `<dir>/tun.toml`. Throws [ConfigException] when it is missing or invalid. */
fun loadConfig(dir: Path): ServerConfig {
    val path = dir.resolve(FILE_NAME)
    if (path.notExists()) throw ConfigException("$path not found; create it (see the README)")
    val text = try {
        path.readText()
    } catch (e: IOException) { // a directory, no permission, or not UTF-8
        val why = e.message ?: e.javaClass.simpleName
        throw ConfigException("$path cannot be read ($why); fix its permissions or contents", e)
    }
    val file = decodeToml(text, path.toString())
    fun fail(problem: String): Nothing = throw ConfigException("$path: $problem")
    if (file.version != 1) fail("version = ${file.version} is not supported; set version = 1")
    val server = file.server
    if (!HOST.matches(server.domain)) {
        fail("[server] domain = \"${server.domain}\" is not a host name; " +
            "set it to a bare host name such as \"tun.example.com\"")
    }
    val port = server.port ?: DEFAULT_PORT
    if (port !in PORTS) fail("[server] port = $port is out of range; use a port from 1 to 65535")
    return ServerConfig(
        domain = server.domain,
        bind = server.bind ?: "127.0.0.1",
        port = port,
        grace = server.grace ?: DEFAULT_GRACE,
        pingInterval = server.pingInterval ?: DEFAULT_PING_INTERVAL,
        tokens = tokens(file.token, ::fail),
    )
}

private fun tokens(raw: List<TomlToken>, fail: (String) -> Nothing): List<Token> {
    val tokens = raw.map { token(it, fail) }
    tokens.groupBy { it.alias }.values.firstOrNull { it.size > 1 }?.let {
        fail("token alias \"${it[0].alias}\" is used twice; give each [[token]] its own alias")
    }
    tokens.groupBy { it.hash }.values.firstOrNull { it.size > 1 }?.let { same ->
        fail("tokens ${same.joinToString { "\"${it.alias}\"" }} share one hash; issue each its own token")
    }
    return tokens
}

private fun token(raw: TomlToken, fail: (String) -> Nothing): Token {
    if (!HASH.matches(raw.hash)) {
        fail("token \"${raw.alias}\": hash must be \"sha256:\" followed by 64 hex chars (see the README)")
    }
    val level = when (raw.level) {
        "random" -> Level.RANDOM
        "named" -> Level.NAMED
        else -> fail("token \"${raw.alias}\": level must be \"random\" or \"named\"")
    }
    return Token(raw.alias, raw.hash.removePrefix("sha256:").lowercase(), level)
}
