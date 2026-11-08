plugins {
    id("tun.kotlin")
    alias(libs.plugins.graalvm.native)
}

dependencies {
    implementation(project(":server"))
    implementation(project(":client"))
}

// the version reaches the native binary as a compiled constant: no resources or manifest to configure for native-image
val generateVersion by tasks.registering {
    val out = layout.buildDirectory.dir("generated/version")
    val ver = project.version.toString()
    inputs.property("version", ver)
    outputs.dir(out)
    doLast {
        out.get().file("tun/app/Version.kt").asFile.apply { parentFile.mkdirs() }
            .writeText("package tun.app\n\ninternal const val VERSION = \"$ver\"\n")
    }
}
kotlin.sourceSets.main { kotlin.srcDir(generateVersion) }

graalvmNative {
    toolchainDetection = true
    binaries.named("main") {
        imageName = "tun"
        mainClass = "tun.app.MainKt"
        javaLauncher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
            vendor = JvmVendorSpec.GRAAL_VM // GraalVM CE, not another native-image-capable JDK such as Liberica NIK
            nativeImageCapable = true
        }
    }
}
