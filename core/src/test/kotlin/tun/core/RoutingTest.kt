package tun.core

import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals

class RoutingTest {
    private val live = Live("laptop", Job(), "mux")
    private val snapshot = Snapshot(mapOf("abcd2345" to live))

    private fun route(host: String?, path: String = "/") = route(host, path, "example.test", snapshot)

    @Test
    fun `apex - health and connect, query ignored, anything else not found`() {
        assertEquals(Route.Health, route("example.test", "/_tun/health"))
        assertEquals(Route.Health, route("example.test", "/_tun/health?x=1"))
        assertEquals(Route.Connect, route("example.test", "/_tun/connect"))
        assertEquals(Route.NotFound, route("example.test", "/x"))
        assertEquals(Route.NotFound, route("example.test", "/_tun/health/x"))
    }

    @Test
    fun `host - case, port and trailing dot are ignored`() {
        assertEquals(Route.Health, route("EXAMPLE.test.:8080", "/_tun/health"))
        assertEquals(Route.Tunnel(live), route("AbCd2345.Example.Test:443"))
        assertEquals(Route.Tunnel(live), route("abcd2345.example.test."))
    }

    @Test
    fun `tunnel - a live single label only`() {
        assertEquals(Route.Tunnel(live), route("abcd2345.example.test", "/_tun/health"))
        assertEquals(Route.NotFound, route("zzzzzzzz.example.test"))
        assertEquals(Route.NotFound, route("x.abcd2345.example.test"))
        assertEquals(Route.NotFound, route(".example.test"))
        assertEquals(Route.NotFound, route("abcd2345example.test"))
    }

    @Test
    fun `other hosts and no host - not found`() {
        assertEquals(Route.NotFound, route("other.test", "/_tun/health"))
        assertEquals(Route.NotFound, route("abcd2345.other.test"))
        assertEquals(Route.NotFound, route(null, "/_tun/health"))
    }
}
