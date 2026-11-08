@file:Suppress("MatchingDeclarationName") // the spec names the file after the client, not its exception

package tun.wire

import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker13
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator
import io.netty.handler.codec.http.websocketx.WebSocketVersion
import io.netty.handler.ssl.SslHandler
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.CancellationException
import java.net.URI
import javax.net.ssl.SSLContext

/** A WebSocket that could not be opened. The message says why, for a person; the channel is closed. */
class WebSocketConnectException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Opens a WebSocket to [url] (`ws://` or `wss://`). `wss://` checks the server certificate against the default trust
 * store and the host name. Messages over [maxMessage] bytes and text messages close the connection, as on the server.
 * Throws [WebSocketConnectException] on any failure: connect, TLS, or an answer other than a valid 101.
 */
suspend fun Wire.connectWebSocket(url: URI, maxMessage: Int): WebSocket {
    val port = if (url.port == -1) defaultPort(url.scheme) else url.port
    val tls = if (url.scheme == "wss") tlsHandler(url.host, port) else null
    val reader = MessageReader()
    val opened = runCatching { bootstrap(tls, reader).connect(url.host, port).awaitOrClose() }
    val channel = opened.getOrElse { throw connectFailure(it, tls) }
    return runCatching { upgrade(channel, reader, url, maxMessage) }.getOrElse {
        reader.discard()
        channel.close()
        throw connectFailure(it, tls)
    }
}

private const val HTTP_PORT = 80
private const val HTTPS_PORT = 443
private const val TLS = "tls"
private const val UPGRADE_AGGREGATOR = "upgrade-aggregator"

/** Enough for any 101 and for the head of an error page; only the upgrade response is aggregated. */
private const val MAX_UPGRADE_RESPONSE = 8192

private fun defaultPort(scheme: String?): Int = when (scheme) {
    "wss" -> HTTPS_PORT
    "ws" -> HTTP_PORT
    else -> throw WebSocketConnectException("$scheme is not ws or wss")
}

private fun tlsHandler(host: String, port: Int): SslHandler {
    val engine = SSLContext.getDefault().createSSLEngine(host, port) // host and port: SNI and the name check
    engine.useClientMode = true
    engine.sslParameters = engine.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
    return SslHandler(engine)
}

private fun Wire.bootstrap(tls: SslHandler?, reader: MessageReader): Bootstrap =
    Bootstrap().group(loop).channel(NioSocketChannel::class.java).handler(object : ChannelInitializer<Channel>() {
        override fun initChannel(ch: Channel) {
            tls?.let { ch.pipeline().addLast(TLS, it) }
            ch.pipeline().addLast(CODEC, HttpClientCodec())
                .addLast(UPGRADE_AGGREGATOR, HttpObjectAggregator(MAX_UPGRADE_RESPONSE))
                .addLast(READER, reader)
        }
    })

/** Sends the upgrade request, checks the 101, then installs the same framing, limits and control as the server. */
private suspend fun upgrade(channel: Channel, reader: MessageReader, url: URI, maxMessage: Int): WebSocket {
    val handshaker = WebSocketClientHandshaker13(url, WebSocketVersion.V13, null, false, null, maxMessage)
    // control and the aggregator pass HTTP objects through, so they go in before the 101 and no frame passes unchecked
    channel.pipeline().addBefore(READER, FINISH_UPGRADE, FinishUpgrade(handshaker))
        .addBefore(READER, CONTROL, WebSocketControl())
        .addBefore(READER, AGGREGATOR, WebSocketFrameAggregator(maxMessage))
    handshaker.handshake(channel).awaitOrClose()
    val failure = when (val outcome = reader.read(channel)) {
        Upgraded -> return WebSocket(channel, reader)
        is Throwable -> outcome
        null -> WebSocketConnectException("the connection closed during the upgrade")
        else -> {
            ReferenceCountUtil.release(outcome)
            WebSocketConnectException("the server did not answer with HTTP")
        }
    }
    throw failure
}

private const val FINISH_UPGRADE = "finish-upgrade"

/** What [FinishUpgrade] passes on after a valid 101. */
private object Upgraded

/**
 * Checks the upgrade response on the event loop, so the WebSocket decoder is in place before the bytes that came with
 * the 101 move on. Passes on [Upgraded] or the failure, then leaves the pipeline.
 */
private class FinishUpgrade(private val handshaker: WebSocketClientHandshaker) : ChannelInboundHandlerAdapter() {
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg !is FullHttpResponse) {
            ctx.fireChannelRead(msg)
            return
        }
        val checked = runCatching { handshaker.finishHandshake(ctx.channel(), msg) } // throws on all but a valid 101
        msg.release()
        ctx.fireChannelRead(checked.exceptionOrNull() ?: Upgraded)
        ctx.pipeline().remove(this)
    }
}

private fun connectFailure(cause: Throwable, tls: SslHandler?): Throwable {
    if (cause is CancellationException) return cause
    val why = tls?.handshakeFuture()?.cause() ?: cause // a failed TLS handshake surfaces as a plain close
    return why as? WebSocketConnectException ?: WebSocketConnectException(why.message ?: why.javaClass.simpleName, why)
}
