package tun.wire

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.EmptyHttpHeaders
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker13
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.ReferenceCountUtil
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** A server-side WebSocket that carries binary messages only. Netty reads only while a coroutine waits in [receive]. */
class WebSocket internal constructor(private val netty: Channel, private val reader: MessageReader) {
    /** The next whole binary message (continuations reassembled), owned by the caller, or `null` at close. */
    suspend fun receive(): ByteBuf? {
        while (true) {
            val msg = reader.read(netty) ?: return null
            if (msg is BinaryWebSocketFrame) return msg.content()
            ReferenceCountUtil.release(msg) // only leftovers of the HTTP codec can get here
        }
    }

    /** Sends [message] as one binary message and takes ownership of it, sent or not. */
    suspend fun send(message: ByteBuf) {
        netty.writeAndFlush(BinaryWebSocketFrame(message)).awaitOrClose()
    }

    /** Sends a close frame, then closes the connection. */
    suspend fun close() {
        reader.discard()
        netty.writeAndFlush(CloseWebSocketFrame()).addListener(ChannelFutureListener.CLOSE)
        netty.closeFuture().awaitOrClose()
    }
}

internal fun isWebSocketUpgrade(request: HttpRequest): Boolean {
    val headers = request.headers()
    return request.method() == HttpMethod.GET &&
        headers.containsValue(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE, true) &&
        headers.containsValue(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET, true) &&
        headers[HttpHeaderNames.SEC_WEBSOCKET_VERSION] == "13" &&
        headers.contains(HttpHeaderNames.SEC_WEBSOCKET_KEY)
}

/** Answers [request] with 101 and swaps the HTTP codec for WebSocket framing, limits and liveness. */
internal suspend fun HttpConnection.upgrade(request: HttpRequest, maxMessage: Int, idleTimeout: Duration): WebSocket {
    val config = WebSocketDecoderConfig.newBuilder().maxFramePayloadLength(maxMessage).build()
    val handshaker = WebSocketServerHandshaker13("ws://${request.headers()[HttpHeaderNames.HOST]}", null, config)
    // installed before the 101 goes out, so no frame can pass unchecked
    val idle = IdleStateHandler(idleTimeout.inWholeMilliseconds.coerceAtLeast(1), 0, 0, TimeUnit.MILLISECONDS)
    netty.pipeline().addBefore(READER, "idle", idle)
        .addBefore(READER, "control", WebSocketControl())
        .addBefore(READER, "aggregator", WebSocketFrameAggregator(maxMessage))
    val head = DefaultFullHttpRequest(
        request.protocolVersion(), request.method(), request.uri(), Unpooled.EMPTY_BUFFER,
        request.headers(), EmptyHttpHeaders.INSTANCE,
    )
    handshaker.handshake(netty, head).awaitOrClose()
    return WebSocket(netty, reader)
}

/** Answers pings, echoes a close, and closes on a text message or when the peer has been idle too long. */
private class WebSocketControl : ChannelInboundHandlerAdapter() {
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            is PingWebSocketFrame -> ctx.writeAndFlush(PongWebSocketFrame(msg.content()))
            is PongWebSocketFrame -> msg.release()
            is CloseWebSocketFrame -> ctx.writeAndFlush(msg).addListener(ChannelFutureListener.CLOSE)
            is TextWebSocketFrame -> ctx.close().also { msg.release() }
            else -> ctx.fireChannelRead(msg)
        }
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt is IdleStateEvent) ctx.close() else ctx.fireUserEventTriggered(evt)
    }
}
