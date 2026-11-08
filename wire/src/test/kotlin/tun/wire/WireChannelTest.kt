package tun.wire

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.embedded.EmbeddedChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** One test per row of the I/O matrix in the Story 1.2 spec. */
class WireChannelTest {
    @Test
    fun `demand - no read is issued while nobody reads`() = wireTest { wire, handlers ->
        val server = serverChannel(wire, handlers)
        val client = wire.connect(tcpClient { connect("127.0.0.1", server.port) })
        val peer = server.channel.await()
        val probe = Probe().also { peer.netty.pipeline().addFirst(it) }
        client.write(bytes(1024))
        delay(SETTLE_MS)
        assertFalse(peer.netty.config().isAutoRead)
        assertFalse(client.netty.config().isAutoRead)
        assertEquals(0, probe.reads.get())
        assertEquals(0L, peer.buffered)
    }

    @Test
    fun `read - an empty inbox issues exactly one read`() = wireTest { wire, handlers ->
        val server = serverChannel(wire, handlers)
        val client = wire.connect(tcpClient { connect("127.0.0.1", server.port) })
        val peer = server.channel.await()
        val probe = Probe().also { peer.netty.pipeline().addFirst(it) }
        client.write(Unpooled.copiedBuffer(byteArrayOf(1, 2, 3)))
        val buf = assertNotNull(peer.read())
        try {
            assertContentEquals(byteArrayOf(1, 2, 3), buf.bytes())
        } finally {
            buf.release()
        }
        delay(SETTLE_MS)
        assertEquals(1, probe.reads.get())
    }

    @Test
    fun `slow consumer - inbound bytes stay within one recv buffer`() = wireTest { wire, handlers ->
        val server = serverChannel(wire, handlers)
        val client = wire.connect(tcpClient { connect("127.0.0.1", server.port) })
        val peer = server.channel.await()
        val probe = Probe { peer.buffered }.also { peer.netty.pipeline().addFirst(it) }
        val writer = launch { repeat(100) { client.write(bytes(RECV_BUFFER_BYTES)) } } // 100 x the limit
        assertNotNull(peer.read()).release() // consume once, then stop
        delay(SETTLE_MS * 2)
        assertEquals(1, probe.reads.get())
        assertTrue(probe.maxBuffered.get() <= RECV_BUFFER_BYTES, "max buffered ${probe.maxBuffered.get()}")
        assertTrue(peer.buffered <= RECV_BUFFER_BYTES)
        writer.cancelAndJoin()
    }

    @Test
    fun `eof - queued buffers come before null`() = runBlocking {
        val reader = DemandReader()
        val embedded = EmbeddedChannel(reader)
        val channel = WireChannel(embedded, reader)
        embedded.writeInbound(Unpooled.copiedBuffer(byteArrayOf(1)), Unpooled.copiedBuffer(byteArrayOf(2)))
        embedded.close()
        listOf<Byte>(1, 2).forEach { expected ->
            val buf = assertNotNull(channel.read())
            assertEquals(expected, buf.readByte())
            buf.release()
        }
        assertNull(channel.read())
        assertEquals(0L, channel.buffered)
    }

    @Test
    fun `close - queued buffers are released, and so is anything that arrives later`() = runBlocking {
        val reader = DemandReader()
        val embedded = EmbeddedChannel(reader)
        val channel = WireChannel(embedded, reader)
        val queued = Unpooled.copiedBuffer(byteArrayOf(1))
        val late = Unpooled.copiedBuffer(byteArrayOf(2))
        embedded.writeInbound(queued)
        reader.discard()
        embedded.writeInbound(late)
        channel.close()
        assertEquals(0, queued.refCnt())
        assertEquals(0, late.refCnt())
        assertEquals(0L, channel.buffered)
    }

    @Test
    fun `close - a waiting read and any later read return null`() = runBlocking {
        val reader = DemandReader()
        val channel = WireChannel(EmbeddedChannel(reader), reader)
        val waiting = async { channel.read() }
        yield() // let the read suspend
        channel.close()
        assertNull(waiting.await())
        assertNull(channel.read())
    }

    @Test
    fun `failure - a peer error ends read with that error`() = runBlocking {
        val reader = DemandReader()
        val embedded = EmbeddedChannel(reader)
        val channel = WireChannel(embedded, reader)
        embedded.pipeline().fireExceptionCaught(IOException("connection reset"))
        assertFailsWith<IOException> { channel.read() }
        assertFalse(embedded.isOpen)
    }

    @Test
    fun `cancel read - the channel closes and nothing stays buffered`() = wireTest { wire, handlers ->
        val probe = Probe()
        val peer = CompletableDeferred<WireChannel>()
        val server = wire.start(tcpServer { handle { ch -> readForever(ch, probe, peer) } }, handlers)
        val client = wire.connect(tcpClient { connect("127.0.0.1", server.port) })
        val serverSide = peer.await()
        while (probe.reads.get() == 0) delay(1)
        handlers.coroutineContext.job.cancelAndJoin()
        assertNull(client.read())
        assertFalse(serverSide.netty.isActive)
        assertEquals(0L, serverSide.buffered)
    }

    @Test
    fun `cancel read - client side, the channel closes`() = wireTest { wire, handlers ->
        val server = serverChannel(wire, handlers)
        val client = wire.connect(tcpClient { connect("127.0.0.1", server.port) })
        val probe = Probe().also { client.netty.pipeline().addFirst(it) }
        val reading = launch { client.read()?.release() }
        while (probe.reads.get() == 0) delay(1)
        reading.cancelAndJoin()
        client.netty.closeFuture().awaitOrClose()
        assertFalse(client.netty.isOpen)
        assertEquals(0L, client.buffered)
    }

    @Test
    fun `handler returns - its connection closes`() = wireTest { wire, handlers ->
        val peer = CompletableDeferred<WireChannel>()
        val server = wire.start(tcpServer { handle { ch -> peer.complete(ch) } }, handlers)
        val client = wire.connect(tcpClient { connect("127.0.0.1", server.port) })
        assertNull(client.read())
        peer.await().netty.closeFuture().awaitOrClose()
        assertFalse(peer.await().netty.isActive)
    }

    @Test
    fun `handler throws - only its connection closes, others keep echoing`() = wireTest { wire, handlers ->
        val peers = Channel<WireChannel>(Channel.UNLIMITED)
        val spec = tcpServer { handle { ch -> peers.send(ch); echoUnlessZero(ch) } }
        val server = wire.start(spec, handlers)
        val failing = wire.connect(tcpClient { connect("127.0.0.1", server.port) })
        val failingPeer = peers.receive()
        val live = wire.connect(tcpClient { connect("127.0.0.1", server.port) })
        failing.write(Unpooled.copiedBuffer(byteArrayOf(0)))
        assertNull(runCatching { failing.read() }.getOrNull()) // EOF or reset, either way no data
        failingPeer.netty.closeFuture().awaitOrClose()
        live.write(Unpooled.copiedBuffer(byteArrayOf(7)))
        assertContentEquals(byteArrayOf(7), readExactly(live, 1))
        assertTrue(handlers.isActive)
    }

    @Test
    fun `server close - the port is freed`() = wireTest { wire, handlers ->
        val server = serverChannel(wire, handlers)
        val port = server.bound.port
        server.bound.close()
        // NIO frees a selector-registered socket only when the event loop next deregisters it, so poll briefly
        val freed = (1..PORT_FREE_TRIES).any { attempt ->
            runCatching { ServerSocket(port).close() }.isSuccess.also { if (!it) delay(attempt.toLong()) }
        }
        assertTrue(freed, "port $port still bound after close")
    }

    @Test
    fun `cancel write - Netty releases the pending buffer exactly once`() = wireTest { wire, handlers ->
        val server = serverChannel(wire, handlers) // never reads, so the write cannot finish
        val client = wire.connect(tcpClient { connect("127.0.0.1", server.port) })
        val buf = ByteBufAllocator.DEFAULT.directBuffer(BIG_WRITE).writeZero(BIG_WRITE)
        val writer = launch { client.write(buf) }
        delay(SETTLE_MS)
        assertTrue(writer.isActive, "the write should still be pending")
        writer.cancelAndJoin()
        while (buf.refCnt() != 0) delay(1)
        delay(SETTLE_MS) // a second release would throw IllegalReferenceCountException on the event loop
        assertEquals(0, buf.refCnt())
        assertFalse(client.netty.isOpen)
    }

    @Test
    fun `echo - 1 MiB round-trips unchanged`() = wireTest { wire, handlers ->
        val echo = tcpServer { handle { ch -> echo(ch) } }
        val server = wire.start(echo, handlers)
        val client = wire.connect(tcpClient { connect("127.0.0.1", server.port) })
        val sent = Random.nextBytes(1024 * 1024)
        val received = async { readExactly(client, sent.size) }
        client.write(Unpooled.copiedBuffer(sent))
        assertContentEquals(sent, received.await())
        client.close()
    }

    @Test
    fun `build time - specs are values and open nothing`() {
        val port = ServerSocket(0).use { it.localPort }
        Wire().use { wire ->
            val server = tcpServer { bind("127.0.0.1", port); handle { } }
            val client = tcpClient { connect("127.0.0.1", port) }
            assertEquals(port, server.port)
            assertEquals(TcpClientSpec("127.0.0.1", port), client)
            assertFalse(wire.started)
        }
        ServerSocket(port).close() // still free: nothing was bound
    }

    private class ServerSide(val bound: BoundServer, val channel: CompletableDeferred<WireChannel>) {
        val port get() = bound.port
    }

    /** A server whose handler hands its channel to the test and then waits. */
    private suspend fun serverChannel(wire: Wire, handlers: CoroutineScope): ServerSide {
        val channel = CompletableDeferred<WireChannel>()
        val spec = tcpServer { handle { ch -> channel.complete(ch); awaitCancellation() } }
        return ServerSide(wire.start(spec, handlers), channel)
    }

    private suspend fun readForever(ch: WireChannel, probe: Probe, peer: CompletableDeferred<WireChannel>) {
        ch.netty.pipeline().addFirst(probe)
        peer.complete(ch)
        ch.read()?.release()
        error("nothing was sent, so read should only end by cancellation")
    }

    private suspend fun echo(ch: WireChannel) {
        while (true) ch.write(ch.read() ?: return)
    }

    private suspend fun echoUnlessZero(ch: WireChannel) {
        while (true) {
            val buf = ch.read() ?: return
            if (buf.getByte(buf.readerIndex()) == 0.toByte()) {
                buf.release()
                error("deliberate handler failure")
            }
            ch.write(buf)
        }
    }

    private suspend fun readExactly(ch: WireChannel, size: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(size)
        while (out.size() < size) {
            val buf = assertNotNull(ch.read())
            out.write(buf.bytes())
            buf.release()
        }
        return out.toByteArray()
    }

    /** Counts Netty reads; with [buffered], also records the inbox peak after every inbound buffer. */
    private class Probe(private val buffered: () -> Long = { 0 }) : ChannelDuplexHandler() {
        val reads = AtomicInteger()
        val maxBuffered = AtomicLong()

        override fun read(ctx: ChannelHandlerContext) {
            reads.incrementAndGet()
            ctx.read()
        }

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            ctx.fireChannelRead(msg)
            maxBuffered.accumulateAndGet(buffered(), ::maxOf)
        }
    }

    private fun wireTest(block: suspend CoroutineScope.(Wire, CoroutineScope) -> Unit) = runBlocking {
        Wire().use { wire ->
            val handlers = CoroutineScope(coroutineContext + Job(coroutineContext.job))
            try {
                withTimeout(10.seconds) { block(wire, handlers) }
            } finally {
                handlers.coroutineContext.job.cancelAndJoin()
            }
        }
    }

    private companion object {
        const val SETTLE_MS = 300L
        const val PORT_FREE_TRIES = 30 // about 0.5 s in total
        const val BIG_WRITE = 64 * 1024 * 1024

        fun bytes(size: Int): ByteBuf = Unpooled.copiedBuffer(Random.nextBytes(size))

        fun ByteBuf.bytes(): ByteArray = ByteArray(readableBytes()).also { readBytes(it) }

        fun <T : Any> assertNotNull(value: T?): T = kotlin.test.assertNotNull(value)
    }
}
