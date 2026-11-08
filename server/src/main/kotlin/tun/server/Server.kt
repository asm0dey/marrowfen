package tun.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import tun.wire.Wire
import java.io.PrintStream
import java.nio.file.Path

private const val USAGE = "usage: tun server [--config-dir <dir>] [--state-dir <dir>]"

/**
 * The `server` mode: loads `tun.toml`, then serves until the process ends. Returns the exit code of a startup
 * failure, which goes to [err] as one plain line.
 */
fun runServer(args: List<String>, err: PrintStream): Int {
    val flags = parseFlags(args) ?: return 2.also { err.println(USAGE) }
    val configDir = Path.of(flags["--config-dir"] ?: "/etc/tun")
    // --state-dir (default /var/lib/tun) is accepted but not touched until Epic 3
    val config = try {
        loadConfig(configDir)
    } catch (e: ConfigException) {
        null.also { err.println("tun: ${e.message}") }
    }
    return config?.let { Wire().use { wire -> runBlocking { serve(it, wire, err) } } } ?: 1
}

private suspend fun CoroutineScope.serve(config: ServerConfig, wire: Wire, err: PrintStream): Int {
    val bound = runCatching { startServer(config, wire, this) }
    val why = bound.exceptionOrNull() ?: awaitCancellation()
    err.println(
        "tun: cannot listen on ${config.bind}:${config.port} (${why.message ?: why.javaClass.simpleName}); " +
            "free the port or change port in tun.toml",
    )
    coroutineContext.cancelChildren() // the registry owner
    return 1
}

/** `--flag value` pairs, or null on an unknown flag or a missing value. */
private fun parseFlags(args: List<String>): Map<String, String>? {
    if (args.size % 2 != 0) return null
    val pairs = args.chunked(2) { it[0] to it[1] }
    return pairs.toMap().takeIf { pairs.all { (flag) -> flag == "--config-dir" || flag == "--state-dir" } }
}
