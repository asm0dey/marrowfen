package tun.wire

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.net.http.WebSocket as JdkWebSocket

/** A response read by [RawHttp]. */
data class RawResponse(val status: Int, val headers: Map<String, String>, val body: String)

/** One raw HTTP/1.1 connection, for keep-alive and exact `Host` headers. Blocking, with a 5 s read timeout. */
class RawHttp(port: Int) : AutoCloseable {
    private val socket = Socket("127.0.0.1", port).apply { soTimeout = TIMEOUT_MS }
    private val input = socket.getInputStream().buffered()

    fun send(bytes: ByteArray) {
        socket.getOutputStream().apply { write(bytes) }.flush()
    }

    /** Sends one request; [host] `null` sends no `Host` header. */
    fun request(path: String, host: String?, method: String = "GET", body: ByteArray = ByteArray(0)): RawResponse? {
        val head = buildString {
            append("$method $path HTTP/1.1\r\n")
            host?.let { append("Host: $it\r\n") }
            if (body.isNotEmpty()) append("Content-Length: ${body.size}\r\n")
            append("\r\n")
        }
        send(head.toByteArray() + body)
        return response()
    }

    /** The next response, or `null` if the server closed the connection first. */
    fun response(): RawResponse? {
        val status = input.line() ?: return null
        val headers = generateSequence { input.line()?.takeIf { it.isNotEmpty() } }
            .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val length = headers["content-length"]?.toInt() ?: 0
        return RawResponse(status.split(' ')[1].toInt(), headers, String(input.readNBytes(length)))
    }

    /** True once the server has closed the connection (reads EOF). */
    fun closedByServer(): Boolean = runCatching { input.read() == -1 }.getOrDefault(true)

    override fun close() = socket.close()

    private fun InputStream.line(): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = read()
            if (b == -1) return null
            if (b == '\n'.code) return out.toString().removeSuffix("\r")
            out.write(b)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5000
    }
}

/** A JDK WebSocket client that queues what it receives. [closed] completes on a close or an error. */
class WsClient private constructor() : JdkWebSocket.Listener {
    val messages = LinkedBlockingQueue<ByteArray>()
    val pongs = LinkedBlockingQueue<ByteArray>()
    val closed = CompletableFuture<String>()
    lateinit var socket: JdkWebSocket
        private set
    private val partial = ByteArrayOutputStream()

    fun sendBinary(bytes: ByteArray, last: Boolean = true) {
        socket.sendBinary(ByteBuffer.wrap(bytes), last).get(TIMEOUT_S, TimeUnit.SECONDS)
    }

    fun nextMessage(): ByteArray? = messages.poll(TIMEOUT_S, TimeUnit.SECONDS)

    /** Waits for the server to close; returns how it ended. */
    fun awaitClosed(): String = closed.get(TIMEOUT_S, TimeUnit.SECONDS)

    override fun onBinary(ws: JdkWebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
        partial.write(ByteArray(data.remaining()).also { data.get(it) })
        if (last) messages.put(partial.toByteArray()).also { partial.reset() }
        ws.request(1)
        return null
    }

    override fun onPong(ws: JdkWebSocket, message: ByteBuffer): CompletionStage<*>? {
        pongs.put(ByteArray(message.remaining()).also { message.get(it) })
        ws.request(1)
        return null
    }

    override fun onClose(ws: JdkWebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
        closed.complete("close $statusCode")
        return null
    }

    override fun onError(ws: JdkWebSocket, error: Throwable) {
        closed.complete("error ${error.javaClass.simpleName}")
    }

    companion object {
        const val TIMEOUT_S = 5L

        /** Connects to `ws://127.0.0.1:<port><path>`, sending [host] as `Host` when given. */
        fun connect(port: Int, path: String = "/", host: String? = null): WsClient {
            val client = WsClient()
            val builder = HttpClient.newHttpClient().newWebSocketBuilder()
            host?.let { builder.header("Host", it) }
            client.socket = builder.buildAsync(URI("ws://127.0.0.1:$port$path"), client).get(TIMEOUT_S, TimeUnit.SECONDS)
            return client
        }
    }
}
