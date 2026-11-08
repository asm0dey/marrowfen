package tun.wire.fixture

import tun.core.Core

// Violation fixture: nothing depends on app, and wire depends on no project module.
object WireUsesApp {
    fun start() = tun.app.main(emptyArray())
}

// Violation fixture: wire depends on Netty only, not on core.
object WireUsesCore {
    fun core() = Core
}
