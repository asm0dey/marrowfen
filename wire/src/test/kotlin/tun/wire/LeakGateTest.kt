package tun.wire

import io.netty.buffer.ByteBufAllocator
import io.netty.util.ResourceLeakDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LeakGateTest {
    @Test
    fun `the suite runs paranoid`() {
        assertEquals(ResourceLeakDetector.Level.PARANOID, ResourceLeakDetector.getLevel())
    }

    @Test
    fun `the gate reports a deliberate leak`() {
        leakOne()
        val leaks = LeakGate.collectLeaks() // drained here, so the gate's own after-each check passes
        assertTrue(leaks.any { "leakOne" in it }, "expected the deliberate leak, got: $leaks")
    }

    @Test
    fun `a released buffer is not a leak`() {
        ByteBufAllocator.DEFAULT.buffer(1).release()
        assertEquals(emptyList(), LeakGate.collectLeaks())
    }

    private fun leakOne() {
        ByteBufAllocator.DEFAULT.buffer(1).writeByte(1)
    }
}
