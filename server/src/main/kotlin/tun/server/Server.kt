package tun.server

import java.io.PrintStream
import java.nio.file.Path

private const val USAGE = "usage: tun server [--config-dir <dir>] [--state-dir <dir>]"

/** The `server` mode: loads `tun.toml` and returns the exit code. Errors go to [err] as one plain line. */
fun runServer(args: List<String>, err: PrintStream): Int {
    val flags = parseFlags(args) ?: return 2.also { err.println(USAGE) }
    val configDir = Path.of(flags["--config-dir"] ?: "/etc/tun")
    // --state-dir (default /var/lib/tun) is accepted but not touched until Epic 3
    val problem = try {
        loadConfig(configDir)
        "listener not implemented" // Story 1.6 starts the listener here
    } catch (e: ConfigException) {
        e.message
    }
    err.println("tun: $problem")
    return 1
}

/** `--flag value` pairs, or null on an unknown flag or a missing value. */
private fun parseFlags(args: List<String>): Map<String, String>? {
    if (args.size % 2 != 0) return null
    val pairs = args.chunked(2) { it[0] to it[1] }
    return pairs.toMap().takeIf { pairs.all { (flag) -> flag == "--config-dir" || flag == "--state-dir" } }
}
