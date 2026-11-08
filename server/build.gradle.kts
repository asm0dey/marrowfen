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
}
