package tun.detekt

import dev.detekt.api.Config
import dev.detekt.test.lintWithContext
import dev.detekt.test.utils.createEnvironment
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class DslBlockBudgetTest {
    private val environment = createEnvironment()

    private fun lintSample(name: String) = DslBlockBudget(Config.empty)
        .lintWithContext(environment, checkNotNull(javaClass.getResource("/samples/$name.kt")).readText())

    @Test
    fun `six statements in a DSL block is a finding`() {
        val findings = lintSample("six-statements")
        assertEquals(1, findings.size)
        assertContains(findings.single().message, "has 6 lines or sub-blocks")
    }

    @Test
    fun `five statements with a sub-block and a one-line when is clean`() {
        assertEquals(emptyList(), lintSample("five-with-one-line-when"))
    }

    @Test
    fun `a when with a multi-line branch counts one per branch`() {
        assertEquals(1, lintSample("multi-line-when").size)
    }

    @Test
    fun `a marker on a supertype of the receiver makes a DSL block`() {
        assertEquals(1, lintSample("supertype-marker").size)
    }

    @Test
    fun `a marker on the function type makes a DSL block`() {
        assertEquals(1, lintSample("function-type-marker").size)
    }

    @Test
    fun `a lambda whose receiver has no DslMarker is not a DSL block`() {
        assertEquals(emptyList(), lintSample("plain-lambda"))
    }
}
