plugins { id("tun.kotlin") }

// AD-1: the only module that may use Netty beyond io.netty.buffer.
dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.netty.codec.http)
    implementation(libs.netty.codec.http2)
}

tasks.test {
    // every wire test runs under the paranoid leak detector, and LeakGate turns a leak into a failure
    systemProperty("io.netty.leakDetection.level", "paranoid")
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
}
