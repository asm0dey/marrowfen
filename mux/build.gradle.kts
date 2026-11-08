plugins { id("tun.kotlin") }

// AD-1: mux may use io.netty.buffer only.
dependencies {
    implementation(project(":wire"))
    implementation(project(":core"))
    implementation(libs.netty.buffer)
    implementation(libs.coroutines.core)
    testImplementation(testFixtures(project(":wire")))
}

tasks.test {
    // every mux test runs under the paranoid leak detector, and the shared LeakGate turns a leak into a failure
    systemProperty("io.netty.leakDetection.level", "paranoid")
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
}
