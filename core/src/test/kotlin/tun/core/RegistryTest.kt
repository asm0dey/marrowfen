package tun.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** One test per registry row of the I/O matrix in the Story 1.5 spec, plus its acceptance criteria. */
class RegistryTest {
    private val tokens = listOf(Token("laptop", sha256Hex("secret"), Level.RANDOM))

    /** Runs [block] with a registry in a child scope that is cancelled afterwards. */
    private fun withRegistry(
        names: () -> String = ::randomName,
        block: suspend CoroutineScope.(Registry<String>) -> Unit,
    ) = runTest {
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext.job))
        block(scope.launchRegistry(tokens, names))
        scope.coroutineContext.job.cancelAndJoin()
    }

    private suspend fun Registry<String>.claimed(job: Job, tunnel: String = "t"): String =
        assertIs<ClaimResult.Claimed>(claim("secret", job, tunnel)).name

    @Test
    fun `sha256Hex - known answer`() {
        assertEquals("2bb80d537b1da3e38bd30361aa855686bde0eacd7162fef6a25fe97bf527a25b", sha256Hex("secret"))
    }

    @Test
    fun `claim ok - a fresh base32 name, registered and published`() = withRegistry { registry ->
        val job = Job()
        val name = registry.claimed(job, "mux-1")
        assertTrue(Regex("[a-z2-7]{8}").matches(name), name)
        assertEquals(mapOf(name to Live("laptop", job, "mux-1")), registry.snapshot.value.sessions)
        job.cancel()
    }

    @Test
    fun `name collision - regenerates until unique`() {
        val names = ArrayDeque(listOf("aaaaaaaa", "aaaaaaaa", "aaaaaaaa", "bbbbbbbb"))
        withRegistry({ names.removeFirst() }) { registry ->
            val jobs = List(2) { Job() }
            assertEquals(listOf("aaaaaaaa", "bbbbbbbb"), jobs.map { registry.claimed(it) })
            assertTrue(names.isEmpty())
            jobs.forEach { it.cancel() }
        }
    }

    @Test
    fun `unknown token - rejected and the snapshot is unchanged`() = withRegistry { registry ->
        val before = registry.snapshot.value
        assertEquals(ClaimResult.Rejected, registry.claim("wrong", Job(), "t"))
        assertEquals(before, registry.snapshot.value)
    }

    @Test
    fun `empty token list - every claim is rejected`() = runTest {
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext.job))
        assertEquals(ClaimResult.Rejected, scope.launchRegistry<String>(emptyList()).claim("secret", Job(), "t"))
        scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `disconnect - the job completing frees the name`() = withRegistry { registry ->
        val job = Job()
        val name = registry.claimed(job)
        job.complete()
        val after = registry.snapshot.first { name !in it.sessions }
        assertEquals(emptyMap(), after.sessions)
    }

    @Test
    fun `disconnect - a stale job does not free a name a later session holds`() {
        val names = ArrayDeque(listOf("aaaaaaaa", "aaaaaaaa", "cccccccc"))
        withRegistry({ names.removeFirst() }) { registry ->
            val old = Job()
            assertEquals("aaaaaaaa", registry.claimed(old))
            old.complete()
            registry.snapshot.first { it.sessions.isEmpty() }
            val new = Job()
            assertEquals("aaaaaaaa", registry.claimed(new))
            registry.commands.send(Command.Disconnected("aaaaaaaa", old))
            val other = Job()
            registry.claimed(other, "probe") // the owner handles commands in order, so the stale one was seen
            assertEquals(new, registry.snapshot.value.sessions.getValue("aaaaaaaa").job)
            listOf(new, other).forEach { it.cancel() }
        }
    }

    @Test
    fun `100 concurrent claims get distinct names`() = withRegistry { registry ->
        val jobs = List(100) { Job() }
        val names = jobs.map { job -> async { registry.claimed(job) } }.awaitAll()
        assertEquals(100, names.toSet().size)
        assertEquals(names.toSet(), registry.snapshot.value.sessions.keys)
        jobs.forEach { it.cancel() }
    }

    @Test
    fun `cancelling the scope leaves nothing running`() = runTest {
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext.job))
        val registry = scope.launchRegistry<String>(tokens)
        val session = Job()
        registry.claimed(session)
        scope.coroutineContext.job.cancelAndJoin()
        assertFails { registry.claim("secret", Job(), "t") } // the owner is gone, so a claim fails, never hangs
        session.cancel() // and its completion hook must not throw
        launch { }.join()
    }
}
