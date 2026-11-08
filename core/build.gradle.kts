plugins { id("tun.kotlin") }

// AD-1: core depends on nothing in the project and on no Netty.
dependencies {
    api(libs.coroutines.core) // the registry API speaks Job and StateFlow
    testImplementation(libs.coroutines.test)
}
