plugins { id("tun.kotlin") }

// AD-1: the only module that may use Netty beyond io.netty.buffer.
dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.netty.codec.http)
    implementation(libs.netty.codec.http2)
}
