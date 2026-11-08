package tun.mux

import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** Every `golden/<name>.hex` vector, checked in both directions against the frame it names. */
class GoldenVectorTest {
    private val token = "0123456789abcdef".repeat(4)
    private val authority = Header(":authority", "myapp.example.com")

    // factories, because a Data frame's payload is consumed by encode
    private val vectors: Map<String, () -> Frame> = mapOf(
        "hello" to { Frame.Hello(1u, token, "myapp", 65536u, 16384u) },
        "hello-no-name" to { Frame.Hello(1u, token, null, 65536u, 16384u) },
        "hello-ok" to { Frame.HelloOk("myapp", "https://myapp.example.com", 65536u, 16384u, 20.seconds) },
        "hello-err" to { Frame.HelloErr(1u, "invalid token") },
        "open" to {
            val head = listOf(
                Header(":method", "POST"), Header(":scheme", "https"), authority,
                Header(":path", "/upload"), Header("content-type", "text/plain"),
            )
            Frame.Open(1u, head)
        },
        "open-end" to {
            val head = listOf(Header(":method", "GET"), Header(":scheme", "https"), authority, Header(":path", "/"))
            Frame.Open(3u, head, end = true)
        },
        "headers" to { Frame.Headers(1u, listOf(Header(":status", "200"), Header("content-type", "text/plain"))) },
        "headers-trailers-end" to { Frame.Headers(1u, listOf(Header("grpc-status", "0")), end = true) },
        "data" to { Frame.Data(1u, Unpooled.copiedBuffer("hello", Charsets.UTF_8)) },
        "data-empty-end" to { Frame.Data(1u, Unpooled.EMPTY_BUFFER, end = true) },
        "window-update" to { Frame.WindowUpdate(1u, 65536u) },
        "reset" to { Frame.Reset(1u, ResetCode.BACKEND_UNREACHABLE) },
    )

    @Test
    fun `every vector file has a table entry and every entry a file`() {
        val dir = File(requireNotNull(javaClass.getResource("/golden")).toURI())
        val files = dir.listFiles { f -> f.name.endsWith(".hex") }.orEmpty().map { it.name.removeSuffix(".hex") }
        assertEquals(vectors.keys.sorted(), files.sorted())
    }

    @Test
    fun `encode produces every vector exactly`() {
        vectors.forEach { (name, frame) ->
            val encoded = encode(frame(), ByteBufAllocator.DEFAULT)
            try {
                assertEquals(hex(name), ByteBufUtil.hexDump(encoded), name)
            } finally {
                encoded.release()
            }
        }
    }

    @Test
    fun `decode yields every vector's frame`() {
        vectors.forEach { (name, frame) ->
            val input = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(hex(name)))
            val decoded = decode(input, 16384u)
            val expected = frame()
            assertEquals(expected, decoded, name)
            (decoded as? Frame.Data)?.payload?.release()
            (expected as? Frame.Data)?.payload?.release()
            assertEquals(0, input.refCnt(), name)
        }
    }

    private fun hex(name: String): String =
        requireNotNull(javaClass.getResource("/golden/$name.hex")).readText().lineSequence()
            .joinToString("") { it.substringBefore('#').filterNot(Char::isWhitespace) }
}
