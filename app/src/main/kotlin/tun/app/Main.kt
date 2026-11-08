package tun.app

import tun.client.defaultConfigFile
import tun.client.runHttp
import tun.client.runLogin
import tun.server.runServer
import java.io.PrintStream
import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE = """usage: tun <mode> [args]
modes:
  server                      run the tunnel server
  login <server-url> <token>  save the server and token for http
  http <port>                 expose a local HTTP app
  --version                   print the version"""

fun main(args: Array<String>) {
    exitProcess(run(args.toList(), System.out, System.err))
}

/** Dispatches a mode and returns the exit code. [configFile] is the client's saved login. */
internal fun run(
    args: List<String>,
    out: PrintStream,
    err: PrintStream,
    configFile: Path = defaultConfigFile(),
): Int = when (args.firstOrNull()) {
    "--version" -> 0.also { out.println("tun $VERSION") }
    "server" -> runServer(args.drop(1), err)
    "login" -> runLogin(args.drop(1), out, err, configFile)
    "http" -> runHttp(args.drop(1), out, err, configFile)
    else -> 2.also { err.println(USAGE) }
}
