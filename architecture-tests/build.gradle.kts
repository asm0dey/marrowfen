plugins { id("tun.kotlin") }

// Test-only module: imports every production module's classes and checks AD-1 over them.
dependencies {
    listOf(":core", ":wire", ":mux", ":server", ":client", ":app").forEach { testImplementation(project(it)) }
    testImplementation(libs.archunit)
    testImplementation(libs.netty.codec.http) // the violation fixtures use Netty on purpose
}
