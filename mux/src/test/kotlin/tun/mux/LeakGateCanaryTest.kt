package tun.mux

import io.netty.buffer.ByteBufAllocator
import io.netty.util.ResourceLeakDetector
import tun.wire.LeakGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Proves the shared LeakGate is active in the mux test JVM, as wire's LeakGateTest does for wire. */
class LeakGateCanaryTest {
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

    private fun leakOne() {
        ByteBufAllocator.DEFAULT.buffer(1).writeByte(1)
    }
}
