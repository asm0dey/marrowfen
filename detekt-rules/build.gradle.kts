import dev.detekt.gradle.Detekt

plugins { id("tun.kotlin") }

dependencies {
    compileOnly(libs.detekt.api)
    testImplementation(libs.detekt.test)
}

// fail the rule tests if a sample stops compiling, so a sample can't pass by being broken
tasks.test { systemProperty("compile-test-snippets", "true") }

// Proves the hot-path budgets in config/detekt/detekt.yml fire, and only in wire/mux:
// the same over-budget file sits under a wire path and a core path.
val detektHotPathFixtures by tasks.registering(Detekt::class) {
    setSource(files("src/fixtures"))
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    ignoreFailures = true
    reports.checkstyle.required = true
}

val verifyHotPathFixtures by tasks.registering {
    val report = detektHotPathFixtures.flatMap { it.reports.checkstyle.outputLocation }
    inputs.file(report)
    doLast {
        val xml = report.get().asFile.readText()
        val files = Regex("""<file name="([^"]+)">(.*?)</file>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .associate { it.groupValues[1].substringAfter("src/fixtures/") to it.groupValues[2] }
        fun rules(path: String) = Regex("""source="detekt\.(\w+)"""").findAll(files[path].orEmpty())
            .map { it.groupValues[1] }.toSet()
        val hot = "wire/src/main/kotlin/HotPath.kt"
        val cold = "core/src/main/kotlin/HotPath.kt"
        check(rules(hot).containsAll(setOf("LongMethod", "NestedBlockDepth"))) { "hot-path budgets did not fire in $hot: $xml" }
        check(rules(cold).intersect(setOf("LongMethod", "NestedBlockDepth")).isEmpty()) { "hot-path budgets leaked into $cold: $xml" }
    }
}
// Proves DslBlockBudget runs in a real build: this module's jar as the detekt plugin (service file, provider),
// the real detekt.yml (`tun:` key) and type resolution (a classpath), over an over-budget sample.
val detektDslFixture by tasks.registering(Detekt::class) {
    setSource(files("src/test/resources/samples/six-statements.kt"))
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    pluginClasspath.setFrom(tasks.jar)
    classpath.setFrom(configurations.runtimeClasspath)
    ignoreFailures = true
    reports.checkstyle.required = true
}

val verifyDslFixture by tasks.registering {
    val report = detektDslFixture.flatMap { it.reports.checkstyle.outputLocation }
    inputs.file(report)
    doLast {
        val xml = report.get().asFile.readText()
        check("source=\"detekt.DslBlockBudget\"" in xml) { "DslBlockBudget did not fire in a real detekt run: $xml" }
    }
}
tasks.check { dependsOn(verifyHotPathFixtures, verifyDslFixture) }
