package tun.wire

import io.netty.buffer.ByteBufAllocator
import io.netty.util.ResourceLeakDetector
import io.netty.util.ResourceLeakDetectorFactory
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.fail

/**
 * Fails any wire test that leaks a Netty buffer. Auto-registered (META-INF/services), so the recording factory is
 * installed when JUnit loads extensions, before any ByteBuf class creates its leak detector.
 */
class LeakGate : AfterEachCallback, AfterAllCallback {
    override fun afterEach(context: ExtensionContext) = check(context)

    // leaks the GC finds only after the last test's afterEach
    override fun afterAll(context: ExtensionContext) = check(context)

    private fun check(context: ExtensionContext) {
        val leaks = collectLeaks()
        if (leaks.isEmpty()) return
        fail("${leaks.size} Netty leak(s) in ${context.displayName}:\n${leaks.joinToString("\n")}")
    }

    companion object {
        private val leaks = ConcurrentLinkedQueue<String>()

        init {
            ResourceLeakDetectorFactory.setResourceLeakDetectorFactory(RecordingFactory)
        }

        /** Collects garbage, lets the detector drain its reference queue, then returns and clears the leaks seen. */
        fun collectLeaks(): List<String> {
            repeat(GC_ROUNDS) {
                System.gc()
                Thread.sleep(GC_PAUSE_MS)
                ByteBufAllocator.DEFAULT.buffer(1).release() // a tracked allocation polls the reference queue
            }
            return generateSequence { leaks.poll() }.toList()
        }

        private const val GC_ROUNDS = 5
        private const val GC_PAUSE_MS = 20L
    }

    private object RecordingFactory : ResourceLeakDetectorFactory() {
        @Suppress("OVERRIDE_DEPRECATION") // deprecated in Netty 4.2, yet still the abstract hook
        override fun <T : Any?> newResourceLeakDetector(
            resource: Class<T>,
            samplingInterval: Int,
            maxActive: Long,
        ): ResourceLeakDetector<T> = Recording(resource, samplingInterval)
    }

    private class Recording<T>(resource: Class<T>, samplingInterval: Int) :
        ResourceLeakDetector<T>(resource, samplingInterval) {
        override fun needReport() = true

        override fun reportTracedLeak(resourceType: String, records: String) {
            leaks += "LEAK $resourceType$records"
        }

        override fun reportUntracedLeak(resourceType: String) {
            leaks += "LEAK $resourceType (untraced)"
        }
    }
}
