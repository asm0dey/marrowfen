package tun.mux

import io.netty.buffer.AbstractByteBufAllocator
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class FrameCodecTest {
    private val maxFrame = 16

    @Test
    fun `encoding strips hop-by-hop headers and lowercases names`() {
        val headers = listOf(
            Header("connection", "close"), Header("keep-alive", "5"), Header("transfer-encoding", "chunked"),
            Header("upgrade", "h2c"), Header("te", "gzip"), Header("te", "trailers"), Header("Host", "a"),
        )
        val decoded = decode(encode(Frame.Open(1, headers), ByteBufAllocator.DEFAULT), Int.MAX_VALUE)
        assertEquals(Frame.Open(1, listOf(Header("te", "trailers"), Header("host", "a"))), decoded)
    }

    @Test
    fun `encode takes ownership of a DATA payload`() {
        val payload = Unpooled.copiedBuffer("hi", Charsets.UTF_8)
        encode(Frame.Data(1, payload), ByteBufAllocator.DEFAULT).release()
        assertEquals(0, payload.refCnt())
    }

    @Test
    fun `a decoded DATA payload is a retained slice the caller releases`() {
        val input = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump("00000001" + "0500" + "00".repeat(maxFrame)))
        val data = decode(input, maxFrame) as Frame.Data
        assertEquals(maxFrame, data.payload.readableBytes())
        data.payload.release()
        assertEquals(0, input.refCnt())
    }

    @Test
    fun `a payload over maxFrame is rejected`() = assertRejected("00000001" + "0500" + "00".repeat(maxFrame + 1))

    @Test
    fun `truncated frames are rejected`() {
        assertRejected("0000000105") // under 6 header bytes
        assertRejected("00000000" + "0200" + "00") // HELLO_ERR code cut short
        assertRejected("00000001" + "0600" + "000001") // WINDOW_UPDATE increment cut short
        assertRejected("00000001" + "0300" + "0005" + "6162") // header name runs past the end
        assertRejected("00000001" + "0300" + "0001" + "61") // header value missing
        assertRejected("00000000" + "0000" + "0001" + "0000" + "0000") // HELLO without window and maxFrame
    }

    @Test
    fun `an unknown type is rejected`() = assertRejected("00000001" + "0800")

    @Test
    fun `bad flags are rejected`() {
        assertRejected("00000001" + "0502") // reserved bit
        assertRejected("00000001" + "0503") // reserved bit with END
        assertRejected("00000001" + "0601" + "00000001") // END on WINDOW_UPDATE
        assertRejected("00000001" + "0701" + "0001") // END on RESET
        assertRejected("00000000" + "0201" + "0001" + "0000") // END on HELLO_ERR
    }

    @Test
    fun `frames on the wrong stream are rejected`() {
        assertRejected("00000001" + "0200" + "0001" + "0000") // HELLO_ERR off stream 0
        assertRejected("00000000" + "0300") // OPEN on stream 0
        assertRejected("00000000" + "0600" + "00000001") // WINDOW_UPDATE on stream 0
    }

    @Test
    fun `bad field values are rejected`() {
        assertRejected("00000001" + "0700" + "0000") // RESET code 0
        assertRejected("00000001" + "0700" + "0006") // RESET code 6
        assertRejected("00000001" + "0600" + "80000000") // increment 2^31
        assertRejected("80000001" + "0500") // streamId 2^31 + 1
        assertRejected("00000001" + "0600" + "00000001" + "00") // trailing byte after a fixed payload
        assertRejected("00000001" + "0700" + "0001" + "0000") // trailing bytes after RESET
    }

    @Test
    fun `strings are length-prefixed in UTF-8 bytes, not chars`() {
        val frame = Frame.Headers(1, listOf(Header("x", "é€")))
        val encoded = encode(frame, ByteBufAllocator.DEFAULT)
        assertEquals(5, encoded.getUnsignedShort(9)) // header 6 + name length 2 + "x" 1
        assertEquals(frame, decode(encoded, maxFrame))
    }

    @Test
    fun `an unencodable frame leaks nothing`() {
        val ping = (Int.MAX_VALUE.toLong() + 1).milliseconds
        assertEncodeRejected(Frame.Headers(1, listOf(Header("x", "v".repeat(65536)))))
        assertEncodeRejected(Frame.Headers(1, listOf(Header("x", "é".repeat(32768))))) // 32768 chars, 65536 bytes
        assertEncodeRejected(Frame.HelloErr(65536, "x"))
        assertEncodeRejected(Frame.HelloOk("a", "u", 1, 1, ping))
        assertEncodeRejected(Frame.WindowUpdate(1, -1))
    }

    @Test
    fun `a DATA frame with an invalid stream id releases its payload`() {
        val payload = Unpooled.copiedBuffer("hi", Charsets.UTF_8)
        assertEncodeRejected(Frame.Data(-1, payload))
        assertEquals(0, payload.refCnt())
    }

    private fun assertEncodeRejected(frame: Frame) {
        val alloc = RecordingAllocator()
        assertFailsWith<MuxProtocolException>(frame.toString().take(80)) { encode(frame, alloc) }
        assertTrue(alloc.handedOut.isNotEmpty())
        alloc.handedOut.forEach { assertEquals(0, it.refCnt(), "leaked by ${frame.toString().take(80)}") }
    }

    /** Records every buffer it hands out, so a test can prove each one was released. */
    private class RecordingAllocator : AbstractByteBufAllocator(false) {
        val handedOut = mutableListOf<ByteBuf>()

        override fun newHeapBuffer(initialCapacity: Int, maxCapacity: Int): ByteBuf =
            Unpooled.buffer(initialCapacity, maxCapacity).also { handedOut += it }

        override fun newDirectBuffer(initialCapacity: Int, maxCapacity: Int): ByteBuf =
            Unpooled.directBuffer(initialCapacity, maxCapacity).also { handedOut += it }

        override fun isDirectBufferPooled() = false
    }

    private fun assertRejected(hex: String) {
        val input = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(hex))
        assertFailsWith<MuxProtocolException>(hex) { decode(input, maxFrame) }
        assertEquals(0, input.refCnt(), "input leaked: $hex")
    }
}
