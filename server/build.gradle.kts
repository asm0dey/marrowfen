plugins {
    id("tun.kotlin")
    id("org.jetbrains.kotlin.plugin.serialization") // compile-time serializers for tun.toml: no reflection metadata
}

// composition root: composes lower layers, no Netty of its own (AD-1)
dependencies {
    implementation(project(":mux"))
    implementation(project(":wire"))
    implementation(project(":core"))
    implementation(libs.ktoml.core)
    implementation(libs.coroutines.core)
    testImplementation(testFixtures(project(":wire")))
    testImplementation(libs.netty.buffer) // tests encode mux frames; main code never sees a ByteBuf
}

tasks.test {
    // every server test runs under the paranoid leak detector, and the shared LeakGate turns a leak into a failure
    systemProperty("io.netty.leakDetection.level", "paranoid")
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
    systemProperty("jdk.httpclient.allowRestrictedHeaders", "host") // tests send Host to the JDK clients' 127.0.0.1
}
