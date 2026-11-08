package tun.server.fixture

import tun.client.Client

// Violation fixture: server and client are separate composition roots.
object ServerUsesClient {
    fun client() = Client
}
