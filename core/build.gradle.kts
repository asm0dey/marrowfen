plugins { id("tun.kotlin") }

// AD-1: core depends on nothing in the project and on no Netty.
dependencies {
    implementation(libs.coroutines.core)
}
