package tun.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import tun.core.Registry
import tun.core.Route
import tun.core.launchRegistry
import tun.core.route
import tun.mux.MuxSession
import tun.mux.asMuxTransport
import tun.wire.BoundServer
import tun.wire.HttpExchange
import tun.wire.Wire
import tun.wire.httpServer
import tun.wire.start
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Serves one visitor request routed to a live tunnel. */
typealias TunnelHandler = suspend (HttpExchange, MuxSession) -> Unit

/** The registry's tunnel handle: completed once HELLO_OK is sent, so no OPEN can precede it. */
internal typealias Tunnel = CompletableDeferred<MuxSession>

/** Until Story 1.8 relays requests. */
suspend fun relayPending(exchange: HttpExchange, @Suppress("UNUSED_PARAMETER", "UnusedParameter") session: MuxSession) =
    exchange.respond(BAD_GATEWAY, "tun: relay not implemented")

/**
 * Starts the registry owner in [scope] and binds `bind:port`. Every request on every connection is routed by its
 * `Host`: the apex serves `/_tun/health` and `/_tun/connect`, a live `<name>.<domain>` goes to [onTunnel], anything
 * else gets 404. Throws when the port cannot be bound.
 */
suspend fun startServer(
    config: ServerConfig,
    wire: Wire,
    scope: CoroutineScope,
    helloTimeout: Duration = 10.seconds,
    onTunnel: TunnelHandler = ::relayPending,
): BoundServer {
    val registry = scope.launchRegistry<Tunnel>(config.tokens)
    val handshake = Handshake(config, registry, helloTimeout)
    val spec = httpServer {
        bind(config.bind, config.port)
        handle { exchange -> serve(exchange, config, registry, handshake, onTunnel) }
    }
    return wire.start(spec, scope)
}

private suspend fun serve(
    exchange: HttpExchange,
    config: ServerConfig,
    registry: Registry<Tunnel>,
    handshake: Handshake,
    onTunnel: TunnelHandler,
) {
    when (val route = route(exchange.host, exchange.path, config.domain, registry.snapshot.value)) {
        Route.Health -> exchange.respond(OK, "ok")
        Route.Connect -> connect(exchange, handshake, config)
        is Route.Tunnel -> tunnel(route.live.tunnel)?.let { onTunnel(exchange, it) } ?: notFound(exchange)
        Route.NotFound -> notFound(exchange)
    }
}

private suspend fun connect(exchange: HttpExchange, handshake: Handshake, config: ServerConfig) {
    val socket = exchange.acceptWebSocket(MAX_MESSAGE, config.pingInterval * DEAD_AFTER_INTERVALS)
        ?: return exchange.respond(UPGRADE_REQUIRED, "tun: /_tun/connect needs a WebSocket upgrade; connect with tun")
    handshake.run(socket.asMuxTransport())
}

/** The session, or `null` if the connection ended before HELLO_OK went out. */
private suspend fun tunnel(handle: Tunnel): MuxSession? = try {
    handle.await()
} catch (_: TunnelClosed) {
    null
}

private suspend fun notFound(exchange: HttpExchange) = exchange.respond(NOT_FOUND, "tun: no tunnel here")

private const val OK = 200
private const val NOT_FOUND = 404
private const val UPGRADE_REQUIRED = 426
private const val BAD_GATEWAY = 502
private const val DEAD_AFTER_INTERVALS = 3

/** A whole mux frame: header plus the server's maxFrame payload. */
private val MAX_MESSAGE = MAX_FRAME.toInt() + 6
