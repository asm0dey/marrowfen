rootProject.name = "tun"

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

include("core", "wire", "mux", "server", "client", "app", "e2e", "detekt-rules", "architecture-tests")
