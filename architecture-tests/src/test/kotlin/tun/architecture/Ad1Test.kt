package tun.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Ad1Test {
    private val production = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("tun")

    // production plus the violation fixtures in this test source set
    private val withFixtures = ClassFileImporter().importPackages("tun")

    @Test
    fun `production classes follow AD-1`() {
        listOf("tun.core", "tun.wire", "tun.mux", "tun.server", "tun.client", "tun.app").forEach { pkg ->
            assertTrue(production.any { it.packageName == pkg }, "no production classes found in $pkg")
        }
        Ad1Rules.all.forEach { it.check(production) }
    }

    @Test
    fun `core may not use Netty or other modules`() {
        assertViolations(Ad1Rules.coreIsPlainKotlin, "CoreUsesNetty", "CoreUsesMux")
    }

    @Test
    fun `Netty may not appear above the wrapper`() {
        assertViolations(Ad1Rules.nettyOnlyInWire, "CoreUsesNetty", "ServerUsesNetty")
    }

    @Test
    fun `mux may use io netty buffer only`() {
        val message = assertViolations(Ad1Rules.muxUsesOnlyNettyBuffer, "MuxUsesChannel")
        assertFalse("MuxUsesBuffer" in message, "the io.netty.buffer exception was reported:\n$message")
    }

    @Test
    fun `dependencies follow the AD-1 graph`() {
        assertViolations(
            Ad1Rules.dependencyGraph,
            "CoreUsesMux", "WireUsesApp", "WireUsesCore", "MuxUsesServer", "ServerUsesClient", "ClientUsesServer",
        )
    }

    private fun assertViolations(rule: ArchRule, vararg fixtures: String): String {
        val message = assertFailsWith<AssertionError> { rule.check(withFixtures) }.message.orEmpty()
        fixtures.forEach { assertTrue(it in message, "$it was not reported by:\n$message") }
        return message
    }
}
