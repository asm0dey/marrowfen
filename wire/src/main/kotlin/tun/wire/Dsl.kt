package tun.wire

import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.InetSocketAddress

@DslMarker
annotation class WireDsl

/** Handles one accepted connection. The channel is closed when the handler returns, fails or is cancelled. */
typealias ConnectionHandler = suspend (WireChannel) -> Unit

/** A TCP server as a value: nothing is bound until [Wire.start]. */
class TcpServerSpec internal constructor(val host: String, val port: Int, internal val handler: ConnectionHandler)

/** A TCP client as a value: nothing connects until [Wire.connect]. */
data class TcpClientSpec(val host: String, val port: Int)

@WireDsl
class TcpServerBuilder internal constructor() {
    private var host = "127.0.0.1"
    private var port = 0
    private var handler: ConnectionHandler? = null

    /** Defaults to `127.0.0.1` and an ephemeral port. */
    fun bind(host: String, port: Int) {
        this.host = host
        this.port = port
    }

    fun handle(handler: ConnectionHandler) {
        this.handler = handler
    }

    internal fun build() = TcpServerSpec(host, port, requireNotNull(handler) { "tcpServer { } needs handle { }" })
}

@WireDsl
class TcpClientBuilder internal constructor() {
    private var target: TcpClientSpec? = null

    fun connect(host: String, port: Int) {
        target = TcpClientSpec(host, port)
    }

    internal fun build() = requireNotNull(target) { "tcpClient { } needs connect(host, port)" }
}

fun tcpServer(block: TcpServerBuilder.() -> Unit): TcpServerSpec = TcpServerBuilder().apply(block).build()

fun tcpClient(block: TcpClientBuilder.() -> Unit): TcpClientSpec = TcpClientBuilder().apply(block).build()

/** A listening server. Closing it stops accepting; live connections belong to their handlers. */
class BoundServer internal constructor(private val channel: Channel) {
    val port: Int get() = (channel.localAddress() as InetSocketAddress).port

    suspend fun close() {
        channel.close().awaitOrClose()
    }
}

/**
 * Binds [spec]. Handlers run under one supervisor that is a child of [scope]: a failing handler closes only its
 * own connection, and cancelling [scope] cancels them all. Closing the server lets the supervisor complete.
 */
suspend fun Wire.start(spec: TcpServerSpec, scope: CoroutineScope): BoundServer {
    val supervisor = SupervisorJob(scope.coroutineContext[Job])
    val handlers = CoroutineScope(scope.coroutineContext + supervisor)
    val bootstrap = ServerBootstrap().group(loop).channel(NioServerSocketChannel::class.java)
        .childHandler(acceptor(spec.handler, handlers))
    val bind = bootstrap.bind(spec.host, spec.port)
    bind.channel().closeFuture().addListener { supervisor.complete() } // also fires when bind fails
    return BoundServer(bind.awaitOrClose())
}

suspend fun Wire.connect(spec: TcpClientSpec): WireChannel {
    val reader = DemandReader()
    val bootstrap = Bootstrap().group(loop).channel(NioSocketChannel::class.java).handler(reader)
    return WireChannel(bootstrap.connect(spec.host, spec.port).awaitOrClose(), reader)
}

private fun acceptor(handler: ConnectionHandler, scope: CoroutineScope) = object : ChannelInitializer<Channel>() {
    override fun initChannel(ch: Channel) {
        val reader = DemandReader()
        ch.pipeline().addLast(reader)
        val wire = WireChannel(ch, reader)
        // invokeOnCompletion also covers a scope cancelled before the handler starts
        scope.launch { handler(wire) }.invokeOnCompletion { wire.abort() }
    }
}
