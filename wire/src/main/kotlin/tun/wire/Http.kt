package tun.wire

import io.netty.channel.Channel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpObject
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.LastHttpContent
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Handles one HTTP/1.1 request. It responds, accepts a WebSocket, or returns without either, which closes the
 * connection. A failure closes the connection too, and goes no further.
 */
typealias HttpHandler = suspend (HttpExchange) -> Unit

/** An HTTP/1.1 server as a value: nothing is bound until [Wire.start]. */
class HttpServerSpec internal constructor(val host: String, val port: Int, internal val handler: HttpHandler)

@WireDsl
class HttpServerBuilder internal constructor() {
    private var host = "127.0.0.1"
    private var port = 0
    private var handler: HttpHandler? = null

    /** Defaults to `127.0.0.1` and an ephemeral port. */
    fun bind(host: String, port: Int) {
        this.host = host
        this.port = port
    }

    fun handle(handler: HttpHandler) {
        this.handler = handler
    }

    internal fun build() = HttpServerSpec(host, port, requireNotNull(handler) { "httpServer { } needs handle { }" })
}

fun httpServer(block: HttpServerBuilder.() -> Unit): HttpServerSpec = HttpServerBuilder().apply(block).build()

/**
 * Binds [spec], as [Wire.start] does for TCP. Requests on one connection are handled one at a time, in order, and the
 * connection is kept alive as `HttpUtil.isKeepAlive` says. Request bodies are streamed, never aggregated.
 */
suspend fun Wire.start(spec: HttpServerSpec, scope: CoroutineScope): BoundServer =
    bindServer(spec.host, spec.port, scope) { ch ->
        val reader = MessageReader()
        ch.pipeline().addLast(CODEC, HttpServerCodec()).addLast(READER, reader)
        val connection = HttpConnection(ch, reader)
        launch { connection.serve(spec.handler) }.invokeOnCompletion { connection.abort() }
    }

internal const val CODEC = "http"
internal const val READER = "reader"

/** One request: its head, and the one answer the handler gives it. */
class HttpExchange internal constructor(private val connection: HttpConnection, private val request: HttpRequest) {
    val method: String get() = request.method().name()

    /** The request target as sent, query included. */
    val path: String get() = request.uri()

    /** The `Host` header, or `null` when there is none. */
    val host: String? get() = request.headers()[HttpHeaderNames.HOST]

    val headers: List<Pair<String, String>> get() = request.headers().map { it.key to it.value }

    internal var state = State.OPEN
    internal var bodyRead = false
    internal val keepAlive: Boolean get() = HttpUtil.isKeepAlive(request)

    /** Sends a complete response with a `text/plain` [body]. A request answers once. */
    suspend fun respond(status: Int, body: String = "") {
        check(state == State.OPEN) { "this request has already been answered" }
        state = State.RESPONDED
        val content = connection.netty.alloc().buffer().writeBytes(body.toByteArray())
        val response = DefaultFullHttpResponse(request.protocolVersion(), HttpResponseStatus.valueOf(status), content)
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
        if (body.isNotEmpty()) response.headers()[HttpHeaderNames.CONTENT_TYPE] = "text/plain; charset=utf-8"
        HttpUtil.setKeepAlive(response, keepAlive)
        connection.netty.writeAndFlush(response).awaitOrClose()
    }

    /**
     * Upgrades the connection in place, or returns `null` when this is not a WebSocket upgrade request (the request
     * is then still unanswered). Messages over [maxMessage] bytes, text messages, and [idleTimeout] without any inbound
     * frame close the connection.
     */
    suspend fun acceptWebSocket(maxMessage: Int, idleTimeout: Duration): WebSocket? {
        check(state == State.OPEN) { "this request has already been answered" }
        if (!isWebSocketUpgrade(request) || !connection.skipBody(this)) return null
        state = State.UPGRADED
        return connection.upgrade(request, maxMessage, idleTimeout)
    }

    internal enum class State { OPEN, RESPONDED, UPGRADED }
}

internal class HttpConnection(val netty: Channel, val reader: MessageReader) {
    suspend fun serve(handler: HttpHandler) {
        while (true) {
            val exchange = HttpExchange(this, nextRequest() ?: return)
            val handled = runCatching { handler(exchange) }
            handled.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            val reusable = handled.isSuccess && exchange.state == HttpExchange.State.RESPONDED && exchange.keepAlive
            if (!reusable || !skipBody(exchange)) return
        }
    }

    /** Reads and releases the rest of [exchange]'s body; `false` at EOF. */
    suspend fun skipBody(exchange: HttpExchange): Boolean {
        while (!exchange.bodyRead) {
            val msg = reader.read(netty)
            val failed = (msg as? HttpObject)?.decoderResult()?.isFailure == true // the decoder now discards input
            exchange.bodyRead = msg is LastHttpContent
            ReferenceCountUtil.release(msg)
            if (msg == null || failed) return false
        }
        return true
    }

    fun abort() {
        reader.discard()
        netty.close()
    }

    /** The next request head, or `null` at EOF or on a request the decoder could not parse. */
    private suspend fun nextRequest(): HttpRequest? {
        val msg = reader.read(netty)
        if (msg is HttpRequest && msg.decoderResult().isSuccess) return msg
        ReferenceCountUtil.release(msg) // null-safe
        return null
    }
}
