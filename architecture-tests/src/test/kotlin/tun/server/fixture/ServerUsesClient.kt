package tun.server.fixture

import tun.client.Login

// Violation fixture: server and client are separate composition roots.
object ServerUsesClient {
    fun client() = Login("wss://example.com", "")
}
