package tun.architecture

import com.tngtech.archunit.base.DescribedPredicate.not
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture

/** AD-1, as ArchUnit rules. Packages are `tun.<module>`. */
object Ad1Rules {
    val coreIsPlainKotlin: ArchRule = noClasses().that().resideInAPackage("tun.core..")
        .should().dependOnClassesThat()
        .resideInAnyPackage("io.netty..", "tun.wire..", "tun.mux..", "tun.server..", "tun.client..", "tun.app..")
        .because("core depends on nothing in the project and on no Netty (AD-1)")

    val nettyOnlyInWire: ArchRule = noClasses().that().resideOutsideOfPackages("tun.wire..", "tun.mux..")
        .should().dependOnClassesThat().resideInAPackage("io.netty..")
        .because("Netty types appear only in wire (AD-1)")

    val muxUsesOnlyNettyBuffer: ArchRule = noClasses().that().resideInAPackage("tun.mux..")
        .should().dependOnClassesThat(resideInAPackage("io.netty..").and(not(resideInAPackage("io.netty.buffer.."))))
        .because("mux may use io.netty.buffer only (AD-1)")

    val dependencyGraph: ArchRule = layeredArchitecture().consideringOnlyDependenciesInLayers()
        .layer("app").definedBy("tun.app..")
        .layer("server").definedBy("tun.server..")
        .layer("client").definedBy("tun.client..")
        .layer("mux").definedBy("tun.mux..")
        .layer("wire").definedBy("tun.wire..")
        .layer("core").definedBy("tun.core..")
        .whereLayer("app").mayNotBeAccessedByAnyLayer()
        .whereLayer("server").mayOnlyBeAccessedByLayers("app")
        .whereLayer("client").mayOnlyBeAccessedByLayers("app")
        .whereLayer("mux").mayOnlyBeAccessedByLayers("server", "client")
        .whereLayer("wire").mayOnlyBeAccessedByLayers("mux", "server", "client")
        .whereLayer("core").mayOnlyBeAccessedByLayers("mux", "server", "client")
        .because("an arrow in the AD-1 graph means 'may depend on'")

    val all = listOf(coreIsPlainKotlin, nettyOnlyInWire, muxUsesOnlyNettyBuffer, dependencyGraph)
}
