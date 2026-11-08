plugins { id("tun.kotlin") }

// AD-1: mux may use io.netty.buffer only.
dependencies {
    implementation(project(":wire"))
    implementation(project(":core"))
    implementation(libs.netty.buffer)
}
