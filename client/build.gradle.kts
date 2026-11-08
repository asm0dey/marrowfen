plugins { id("tun.kotlin") }

// composition root: composes lower layers, no Netty of its own (AD-1)
dependencies {
    implementation(project(":mux"))
    implementation(project(":wire"))
    implementation(project(":core"))
}
