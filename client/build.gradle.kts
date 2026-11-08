plugins {
    id("tun.kotlin")
    id("org.jetbrains.kotlin.plugin.serialization") // compile-time serializers for config.toml: no reflection metadata
}

// composition root: composes lower layers, no Netty of its own (AD-1)
dependencies {
    implementation(project(":mux"))
    implementation(project(":wire"))
    implementation(project(":core"))
    implementation(libs.ktoml.core)
    implementation(libs.coroutines.core)
    testImplementation(testFixtures(project(":wire")))
    testImplementation(libs.netty.buffer) // the fake server speaks mux frames; main code never sees a ByteBuf
}

tasks.test {
    // every client test runs under the paranoid leak detector, and the shared LeakGate turns a leak into a failure
    systemProperty("io.netty.leakDetection.level", "paranoid")
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
}
