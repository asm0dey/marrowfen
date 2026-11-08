import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("dev.detekt")
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.GRAAL_VM // the installed GraalVM CE is the one JDK for compile, test and native-image
        nativeImageCapable = true
    }
}

detekt {
    buildUponDefaultConfig = true
    // the hot-path budgets (LongMethod 20, NestedBlockDepth 3, scoped to wire/mux) and the DSL budget live here
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

dependencies {
    if (project.path != ":detekt-rules") detektPlugins(project(":detekt-rules"))
    testImplementation(platform(lib("junit-bom")))
    testImplementation(lib("junit-jupiter"))
    testImplementation(lib("kotlin-test"))
    testRuntimeOnly(lib("junit-launcher"))
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

// the plain `detekt` task skips rules that need type resolution (DslBlockBudget), so gate the build on these too
tasks.named("check") { dependsOn("detektMain", "detektTest") }
