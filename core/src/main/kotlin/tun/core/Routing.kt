@file:Suppress("MatchingDeclarationName") // the spec names the file after routing, not the Route type

package tun.core

/** Where one request goes, decided by its `Host` and path alone. */
sealed interface Route<out T> {
    /** `/_tun/health` on the apex. */
    data object Health : Route<Nothing>

    /** `/_tun/connect` on the apex: the control WebSocket. */
    data object Connect : Route<Nothing>

    /** `<name>.<domain>` for a live name. */
    data class Tunnel<T>(val live: Live<T>) : Route<T>

    data object NotFound : Route<Nothing>
}

private const val HEALTH = "/_tun/health"
private const val CONNECT = "/_tun/connect"

/**
 * Routes a request for [host] (the `Host` header, `null` when absent) and [path] (the request target) under [domain].
 * Hosts match case-insensitively, without a `:port` or trailing dot; paths match without their query. Only a single
 * label before the domain names a tunnel.
 */
fun <T> route(host: String?, path: String, domain: String, snapshot: Snapshot<T>): Route<T> {
    val name = host?.let(::bareHost)
    val apex = bareHost(domain)
    val label = name?.removeSuffix(".$apex")?.takeIf { it != name && it.isNotEmpty() && '.' !in it }
    return when {
        name == apex -> apexRoute(path.substringBefore('?'))
        label != null -> snapshot.sessions[label]?.let { Route.Tunnel(it) } ?: Route.NotFound
        else -> Route.NotFound
    }
}

private fun apexRoute(path: String): Route<Nothing> = when (path) {
    HEALTH -> Route.Health
    CONNECT -> Route.Connect
    else -> Route.NotFound
}

private fun bareHost(host: String): String = host.substringBefore(':').removeSuffix(".").lowercase()
