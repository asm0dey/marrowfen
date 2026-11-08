package tun.client.fixture

import tun.server.Server

// Violation fixture: server and client are separate composition roots.
object ClientUsesServer {
    fun server() = Server
}
