package tun.mux

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.Unpooled
import io.netty.buffer.ByteBufUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import tun.mux.Role.CLIENT
import tun.mux.Role.SERVER
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** One test per row of the I/O matrix in the Story 1.4 spec, plus the named risk tests. */
class MuxStreamTest {
    @Test
    fun `ids - the server allocates from 1 and the client cannot open`() = muxTest {
        val (server, client) = connected()
        assertEquals(listOf(1u, 2u, 3u), List(3) { server.open(HEAD).id })
        assertFailsWith<IllegalStateException> { client.open(HEAD) }
        assertFailsWith<IllegalStateException> { nextStreamId(UInt.MAX_VALUE) }
        assertEquals(UInt.MAX_VALUE, nextStreamId(UInt.MAX_VALUE - 1u))
    }

    @Test
    fun streamIdNeverReused() = muxTest {
        val (server, raw) = withRaw(SERVER)
        val streams = List(3) { server.open(HEAD, end = true) }
        raw.send(Frame.Headers(2u, HEAD, end = true))
        assertIs<Frame.Headers>(streams[1].receive())
        assertNull(streams[1].receive())
        assertEquals(2, server.table.size, "stream 2 closed once both ENDs were done")
        assertEquals(4u, server.open(HEAD).id)
        assertEquals(listOf(1u, 2u, 3u, 4u), List(4) { (raw.next() as Frame.Open).streamId })
    }

    @Test
    fun `unknown or closed streams are ignored without a reply`() = muxTest {
        val opened = Channel<MuxStream>(Channel.UNLIMITED)
        val (_, raw) = withRaw(CLIENT) { opened.send(it); awaitCancellation() }
        raw.sendToEveryType(9u)
        raw.send(Frame.Open(1u, HEAD, end = true))
        raw.send(Frame.Open(1u, HEAD)) // id <= the highest seen
        val first = opened.receive()
        assertIs<Frame.Headers>(first.receive())
        assertNull(first.receive())
        first.headers(HEAD, end = true) // both ENDs done: stream 1 is closed
        assertEquals(Frame.Headers(1u, HEAD, end = true), raw.next())
        raw.sendToEveryType(1u)
        raw.send(Frame.Open(2u, HEAD))
        opened.receive().headers(HEAD)
        assertEquals(Frame.Headers(2u, HEAD), raw.next(), "no reply to the ignored frames came first")
        assertNull(opened.tryReceive().getOrNull(), "the repeated OPEN opened nothing")
    }

    @Test
    fun `window - exactly W bytes cross while the consumer never acks`() = muxTest {
        val received = AtomicInteger()
        val (server, _) = connected(clientOpen = { it.drain(ack = false, into = received) })
        val stream = server.open(HEAD)
        val writer = async { stream.write(bytes(3 * WINDOW_BYTES)) }
        while (received.get() < WINDOW_BYTES) delay(1)
        delay(SETTLE_MS)
        assertEquals(WINDOW_BYTES, received.get())
        assertTrue(writer.isActive, "write stays suspended")
    }

    @Test
    fun `ack - the peer gets WINDOW_UPDATE n and the sender resumes`() = muxTest {
        val (_, rawSender) = withRaw(CLIENT) { stream -> stream.drain(ack = true) }
        rawSender.send(Frame.Open(1u, HEAD))
        rawSender.send(Frame.Data(1u, bytes(10)))
        assertEquals(Frame.WindowUpdate(1u, 10u), rawSender.next())

        val (server, rawReceiver) = withRaw(SERVER)
        val stream = server.open(HEAD)
        assertIs<Frame.Open>(rawReceiver.next())
        val writer = async { stream.write(bytes(3 * WINDOW_BYTES), end = true) }
        assertEquals(WINDOW_BYTES, rawReceiver.dataBytes(WINDOW_BYTES))
        rawReceiver.send(Frame.WindowUpdate(1u, (2 * WINDOW_BYTES).toUInt()))
        assertEquals(2 * WINDOW_BYTES, rawReceiver.dataBytes(2 * WINDOW_BYTES))
        writer.await()
    }

    @Test
    fun `over-window DATA resets with flow_control and releases the queue`() = muxTest {
        val ready = CompletableDeferred<MuxStream>()
        val (_, raw) = withRaw(CLIENT) { stream ->
            stream.receive()
            ready.complete(stream)
            awaitCancellation()
        }
        raw.send(Frame.Open(1u, HEAD))
        val stream = ready.await()
        raw.send(Frame.Data(1u, bytes(10))) // queued, never read
        raw.send(Frame.Data(1u, bytes(WINDOW_BYTES - 10 + 1)))
        assertEquals(Frame.Reset(1u, ResetCode.FLOW_CONTROL), raw.next())
        assertEquals(ResetCode.FLOW_CONTROL, assertFailsWith<MuxStreamReset> { stream.receive() }.code)
    }

    @Test
    fun `credit overflow or a zero increment resets the stream`() = muxTest {
        val (server, raw) = withRaw(SERVER)
        val (zero, overflow) = server.open(HEAD) to server.open(HEAD)
        repeat(2) { raw.next() }
        raw.send(Frame.WindowUpdate(zero.id, 0u))
        assertEquals(Frame.Reset(zero.id, ResetCode.PROTOCOL_ERROR), raw.next())
        raw.send(Frame.WindowUpdate(overflow.id, UInt.MAX_VALUE))
        assertEquals(Frame.Reset(overflow.id, ResetCode.FLOW_CONTROL), raw.next())
        assertEquals(ResetCode.PROTOCOL_ERROR, assertFailsWith<MuxStreamReset> { zero.write(bytes(1)) }.code)
        assertEquals(ResetCode.FLOW_CONTROL, assertFailsWith<MuxStreamReset> { overflow.write(bytes(1)) }.code)
    }

    @Test
    fun halfClose() = muxTest {
        val handled = CompletableDeferred<Int>()
        val (server, client) = connected(clientOpen = { stream ->
            val got = stream.drain(ack = true)
            repeat(3) { stream.write(bytes(4)) }
            stream.write(bytes(4), end = true)
            handled.complete(got)
        })
        val stream = server.open(HEAD)
        stream.write(bytes(4), end = true)
        assertFailsWith<IllegalStateException> { stream.write(bytes(1)) }
        assertEquals(16, stream.drain(ack = true), "the peer's DATA still arrives after our END")
        assertEquals(4, handled.await())
        assertEquals(0, server.table.size)
        assertEquals(0, client.table.size)
    }

    @Test
    fun `DATA or HEADERS after the peer's END resets with protocol_error`() = muxTest {
        val (_, raw) = withRaw(CLIENT)
        raw.send(Frame.Open(1u, HEAD))
        raw.send(Frame.Data(1u, bytes(2), end = true))
        raw.send(Frame.Data(1u, bytes(2)))
        assertEquals(Frame.Reset(1u, ResetCode.PROTOCOL_ERROR), raw.next())
        raw.send(Frame.Open(3u, HEAD, end = true))
        raw.send(Frame.Headers(3u, HEAD))
        assertEquals(Frame.Reset(3u, ResetCode.PROTOCOL_ERROR), raw.next())
    }

    @Test
    fun `reset - the peer's receive and write throw and its queue is released`() = muxTest {
        val first = CompletableDeferred<MuxStream>()
        val second = CompletableDeferred<MuxStream>()
        val (server, _) = connected(clientOpen = {
            (if (it.id == 1u) first else second).complete(it)
            awaitCancellation()
        })
        val stream = server.open(HEAD)
        stream.write(bytes(10)) // queued at the client, never read
        stream.reset(ResetCode.REFUSED)
        val other = server.open(HEAD) // the reader handles frames in order, so the reset came first
        val peer = first.await().also { second.await().reset(ResetCode.BACKEND_UNREACHABLE) }
        assertEquals(ResetCode.REFUSED, assertFailsWith<MuxStreamReset> { peer.receive() }.code)
        assertEquals(ResetCode.REFUSED, assertFailsWith<MuxStreamReset> { peer.write(bytes(1)) }.code)
        assertEquals(ResetCode.BACKEND_UNREACHABLE, assertFailsWith<MuxStreamReset> { other.receive() }.code)
    }

    @Test
    fun `an undecodable frame fails the session and closes the transport`() = muxTest {
        val opened = CompletableDeferred<MuxStream>()
        val (client, raw) = withRaw(CLIENT) { opened.complete(it); awaitCancellation() }
        raw.send(Frame.Open(1u, HEAD))
        raw.send(Frame.Data(1u, bytes(4))) // queued, never read
        val stream = opened.await()
        raw.transport.send(Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump("00000001" + "0800"))) // unknown type
        assertNull(raw.next(), "the session closed the transport")
        client.join()
        val failure = assertFailsWith<CancellationException> { stream.receive() }
        assertIs<MuxProtocolException>(failure.cause)
    }

    @Test
    fun `a closed or failing transport ends the session`() = muxTest {
        val (server, raw) = withRaw(SERVER)
        val stream = server.open(HEAD)
        raw.close()
        server.join()
        assertFailsWith<CancellationException> { stream.receive() }
        assertFailsWith<IllegalStateException> { server.open(HEAD) }

        val broken = start(SERVER, FailingTransport)
        broken.join()
        assertFailsWith<IllegalStateException> { broken.open(HEAD) }
    }

    @Test
    fun `cancelling a blocked write or receive sends RESET cancel`() = muxTest {
        val (server, raw) = withRaw(SERVER)
        val stream = server.open(HEAD)
        assertIs<Frame.Open>(raw.next())
        val writer = launch(start = CoroutineStart.UNDISPATCHED) { stream.write(bytes(2 * WINDOW_BYTES)) }
        assertEquals(WINDOW_BYTES, raw.dataBytes(WINDOW_BYTES))
        writer.cancelAndJoin()
        assertEquals(Frame.Reset(1u, ResetCode.CANCEL), raw.next())

        val other = server.open(HEAD)
        assertIs<Frame.Open>(raw.next())
        raw.send(Frame.Headers(other.id, HEAD))
        assertIs<Frame.Headers>(other.receive())
        launch(start = CoroutineStart.UNDISPATCHED) { other.receive() }.cancelAndJoin()
        assertEquals(Frame.Reset(other.id, ResetCode.CANCEL), raw.next())
    }

    @Test
    fun `closing the session fails every stream and releases what it holds`() = muxTest {
        val opened = CompletableDeferred<MuxStream>()
        val (server, client) = connected(clientOpen = { opened.complete(it); awaitCancellation() })
        val stream = server.open(HEAD)
        val writer = async { stream.write(bytes(3 * WINDOW_BYTES)) } // queues W at the client, then blocks
        val peer = opened.await()
        delay(SETTLE_MS)
        client.close()
        server.close()
        assertFailsWith<CancellationException> { writer.await() }
        assertFailsWith<CancellationException> { peer.receive() }
    }

    @Test
    fun deadlock() = muxTest {
        val clientGot = CompletableDeferred<Int>()
        val (server, _) = connected(clientOpen = { stream ->
            if (stream.id == 1u) delay(SETTLE_MS).also { clientGot.complete(stream.drain(ack = true)) }
            stream.write(bytes(if (stream.id == 1u) 0 else BIG), end = true) // each handler finishes its stream
        })
        val up = server.open(HEAD)
        val down = server.open(HEAD, end = true)
        val writer = async { up.write(bytes(BIG), end = true) }
        delay(SETTLE_MS) // both windows are exhausted, in opposite directions
        assertEquals(BIG, down.drain(ack = true))
        assertEquals(BIG, clientGot.await())
        writer.await()
    }

    @Test
    fun `frames that cost no credit are bounded`() = muxTest {
        val (_, raw) = withRaw(CLIENT)
        raw.send(Frame.Open(1u, HEAD))
        raw.send(Frame.Data(1u, bytes(0)))
        assertEquals(Frame.Reset(1u, ResetCode.PROTOCOL_ERROR), raw.next())
        raw.send(Frame.Open(3u, HEAD))
        repeat(9) { raw.send(Frame.Headers(3u, HEAD)) }
        assertEquals(Frame.Reset(3u, ResetCode.PROTOCOL_ERROR), raw.next())
    }

    @Test
    fun `ack may not exceed the bytes delivered and not yet acked`() = muxTest {
        val opened = CompletableDeferred<MuxStream>()
        val (_, raw) = withRaw(CLIENT) { opened.complete(it); awaitCancellation() }
        raw.send(Frame.Open(1u, HEAD))
        raw.send(Frame.Data(1u, bytes(4)))
        val stream = opened.await()
        assertIs<Frame.Headers>(stream.receive())
        (stream.receive() as Frame.Data).payload.release()
        assertFailsWith<IllegalArgumentException> { stream.ack(5) }
        stream.ack(4)
        assertEquals(Frame.WindowUpdate(1u, 4u), raw.next())
        assertFailsWith<IllegalArgumentException> { stream.ack(1) }
    }

    @Test
    fun `an empty write without END sends nothing`() = muxTest {
        val (server, raw) = withRaw(SERVER)
        val stream = server.open(HEAD)
        stream.write(bytes(0))
        stream.write(bytes(0), end = true)
        assertIs<Frame.Open>(raw.next())
        val data = raw.next() as Frame.Data
        data.payload.release()
        assertTrue(data.end)
    }

    @Test
    fun `a server ignores a peer OPEN`() = muxTest {
        var ran = false
        val (server, raw) = withRaw(SERVER) { ran = true }
        raw.send(Frame.Open(5u, HEAD))
        assertEquals(1u, server.open(HEAD).id)
        assertEquals(Frame.Open(1u, HEAD), raw.next(), "no reply to the peer OPEN came first")
        assertTrue(!ran, "onOpen never ran")
    }

    @Test
    fun `a RESET on a live stream is never answered`() = muxTest {
        val (_, raw) = withRaw(CLIENT) { stream ->
            if (stream.id == 3u) stream.headers(HEAD)
            awaitCancellation()
        }
        raw.send(Frame.Open(1u, HEAD))
        raw.send(Frame.Reset(1u, ResetCode.CANCEL))
        raw.send(Frame.Open(3u, HEAD))
        assertEquals(Frame.Headers(3u, HEAD), raw.next())
    }

    @Test
    fun `a handler that throws or returns early resets its stream with cancel`() = muxTest {
        val (_, raw) = withRaw(CLIENT) { stream ->
            when (stream.id) {
                1u -> error("handler failure")
                3u -> Unit
                else -> {
                    stream.receive()
                    stream.headers(HEAD, end = true)
                    stream.drain(ack = true)
                }
            }
        }
        raw.send(Frame.Open(1u, HEAD))
        raw.send(Frame.Open(3u, HEAD))
        val resets = setOf(raw.next(), raw.next())
        assertEquals(setOf(Frame.Reset(1u, ResetCode.CANCEL), Frame.Reset(3u, ResetCode.CANCEL)), resets)
        raw.send(Frame.Open(5u, HEAD))
        assertEquals(Frame.Headers(5u, HEAD, end = true), raw.next())
        raw.send(Frame.Data(5u, bytes(4)))
        assertEquals(Frame.WindowUpdate(5u, 4u), raw.next())
        raw.send(Frame.Data(5u, bytes(0), end = true)) // stream 5 finishes: no RESET for it
        raw.send(Frame.Open(7u, HEAD))
        assertEquals(Frame.Headers(7u, HEAD, end = true), raw.next())
    }

    @Test
    fun `a failed send ends the session even if receive ignores cancellation`() = muxTest {
        val session = start(SERVER, SendFailsTransport())
        val stream = session.open(HEAD)
        session.join()
        assertFailsWith<CancellationException> { stream.receive() }
    }

    @Test
    fun `a head that cannot be sent resets its stream and the session stays up`() = muxTest {
        val (server, raw) = withRaw(SERVER)
        val unencodable = server.open(listOf(Header("x", "y".repeat(70_000))))
        val oversized = server.open(listOf(Header("x", "y".repeat(MAX_FRAME.toInt()))))
        assertEquals(Frame.Reset(unencodable.id, ResetCode.PROTOCOL_ERROR), raw.next())
        assertEquals(Frame.Reset(oversized.id, ResetCode.PROTOCOL_ERROR), raw.next())
        assertEquals(ResetCode.PROTOCOL_ERROR, assertFailsWith<MuxStreamReset> { oversized.receive() }.code)
        val fine = server.open(HEAD)
        assertEquals(Frame.Open(fine.id, HEAD), raw.next())
    }

    private class Mux(scope: CoroutineScope) : CoroutineScope by scope {
        val sessions = mutableListOf<MuxSession>()

        fun start(role: Role, transport: MuxTransport, onOpen: StreamHandler = HOLD) =
            spec(role).start(transport, this, onOpen).also { sessions += it }

        fun connected(serverOpen: StreamHandler = HOLD, clientOpen: StreamHandler = HOLD) = run {
            val (a, b) = InMemoryTransport.pair()
            start(SERVER, a, serverOpen) to start(CLIENT, b, clientOpen)
        }

        fun withRaw(role: Role, onOpen: StreamHandler = HOLD): Pair<MuxSession, RawPeer> {
            val (a, b) = InMemoryTransport.pair()
            return start(role, a, onOpen) to RawPeer(b)
        }
    }

    /** The other end, driven frame by frame. */
    private class RawPeer(val transport: InMemoryTransport) {
        suspend fun send(frame: Frame) = transport.send(encode(frame, ByteBufAllocator.DEFAULT))

        suspend fun next(): Frame? = transport.receive()?.let { decode(it, UInt.MAX_VALUE) }

        suspend fun close() = transport.close()

        suspend fun sendToEveryType(id: UInt) {
            send(Frame.Data(id, bytes(4)))
            send(Frame.Headers(id, HEAD))
            send(Frame.WindowUpdate(id, 5u))
            send(Frame.Reset(id, ResetCode.CANCEL))
        }

        /** Reads DATA frames, releasing them, until [total] payload bytes have arrived. */
        suspend fun dataBytes(total: Int): Int {
            var got = 0
            while (got < total) {
                val payload = (next() as Frame.Data).payload
                got += payload.readableBytes()
                payload.release()
            }
            return got
        }
    }

    /** send always fails; receive ignores cancellation and returns only once closed. */
    private class SendFailsTransport : MuxTransport {
        private val closed = CompletableDeferred<Unit>()

        override suspend fun send(message: ByteBuf) {
            message.release()
            throw IOException("broken")
        }

        override suspend fun receive(): ByteBuf? = withContext(NonCancellable) { closed.await() }.let { null }

        override suspend fun close() {
            closed.complete(Unit)
        }
    }

    private object FailingTransport : MuxTransport {
        override suspend fun send(message: ByteBuf) {
            message.release()
        }

        override suspend fun receive(): ByteBuf = throw IOException("broken")

        override suspend fun close() = Unit
    }

    private companion object {
        const val WINDOW_BYTES = 16
        const val BIG = 1000
        const val MAX_FRAME = 12u // under the window, so writes split by both; over a 10-byte head
        const val SETTLE_MS = 200L
        val HEAD = listOf(Header(":path", "/"))
        val HOLD: StreamHandler = { awaitCancellation() } // keeps the stream open until the session closes

        fun spec(role: Role) = muxSession {
            role(role)
            local(WINDOW_BYTES.toUInt(), MAX_FRAME)
            peer(WINDOW_BYTES.toUInt(), MAX_FRAME)
        }

        fun muxTest(block: suspend Mux.() -> Unit) = runBlocking {
            withTimeout(10.seconds) {
                val mux = Mux(this)
                try {
                    mux.block()
                } finally {
                    mux.sessions.forEach { it.close() }
                }
            }
        }

        fun bytes(n: Int): ByteBuf = ByteBufAllocator.DEFAULT.buffer(n).writeBytes(ByteArray(n) { it.toByte() })

        /** Receives until END, releasing DATA and acking it when [ack]; returns the payload bytes seen. */
        suspend fun MuxStream.drain(ack: Boolean, into: AtomicInteger = AtomicInteger()): Int {
            while (true) {
                val frame = receive() ?: return into.get()
                if (frame !is Frame.Data) continue
                val n = frame.payload.readableBytes()
                frame.payload.release()
                into.addAndGet(n)
                if (ack) ack(n)
            }
        }
    }
}
