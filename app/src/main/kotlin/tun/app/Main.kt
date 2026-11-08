package tun.app

import tun.server.runServer
import java.io.PrintStream
import kotlin.system.exitProcess

private const val USAGE = """usage: tun <mode> [args]
modes:
  server       run the tunnel server
  http <port>  expose a local HTTP app
  --version    print the version"""

fun main(args: Array<String>) {
    exitProcess(run(args.toList(), System.out, System.err))
}

/** Dispatches a mode and returns the exit code. `http` is a stub until Story 1.7. */
internal fun run(args: List<String>, out: PrintStream, err: PrintStream): Int = when (args.firstOrNull()) {
    "--version" -> 0.also { out.println("tun $VERSION") }
    "server" -> runServer(args.drop(1), err)
    "http" -> 1.also { err.println("not implemented") }
    else -> 2.also { err.println(USAGE) }
}
