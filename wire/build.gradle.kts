plugins {
    id("tun.kotlin")
    `java-test-fixtures` // LeakGate, shared with the mux tests
}

// AD-1: the only module that may use Netty beyond io.netty.buffer.
dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.netty.codec.http)
    implementation(libs.netty.codec.http2)
    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(libs.kotlin.test)
    testFixturesImplementation(libs.netty.buffer)
}

tasks.test {
    // every wire test runs under the paranoid leak detector, and LeakGate turns a leak into a failure
    systemProperty("io.netty.leakDetection.level", "paranoid")
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
}
