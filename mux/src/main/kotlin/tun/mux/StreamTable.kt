package tun.mux

/** A session's live streams and its stream ids. Guarded by [MuxSession.lock]. */
internal class StreamTable(private val role: Role) {
    private val streams = HashMap<UInt, MuxStream>()
    private var lastId = 0u // the last id allocated (server) or accepted (client)
    private var closed = false

    val size get() = streams.size

    fun allocate(): UInt {
        check(role == Role.SERVER) { "only the server opens streams" }
        check(!closed) { "mux session closed" }
        lastId = nextStreamId(lastId)
        return lastId
    }

    /** Whether the peer may open [id]: only the server allocates ids, and they only go up. */
    fun admit(id: UInt): Boolean {
        if (role == Role.SERVER || closed || id <= lastId) return false
        lastId = id
        return true
    }

    fun add(stream: MuxStream) {
        streams[stream.id] = stream
    }

    fun get(id: UInt): MuxStream? = streams[id]

    /** The stream a peer frame is for, or `null` when it is unknown or both ENDs are done: such frames are ignored. */
    fun route(id: UInt): MuxStream? = streams[id]?.takeUnless { it.done }

    fun forget(stream: MuxStream) {
        streams.remove(stream.id)
    }

    /** Refuses new streams and returns the live ones, for the session to fail. */
    fun close(): List<MuxStream> {
        closed = true
        return streams.values.toList()
    }
}
