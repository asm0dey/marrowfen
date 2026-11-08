package tun.client.fixture

import tun.server.ServerConfig

// Violation fixture: server and client are separate composition roots.
object ClientUsesServer {
    fun server() = ServerConfig::class
}
