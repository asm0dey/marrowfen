package tun.mux

// The reader's half of a session: applies one decoded peer frame under the session lock, never suspending.

/** Non-END HEADERS a stream accepts, which cost no credit: room for 1xx heads and the head. */
private const val MAX_HEADS = 8

internal fun MuxSession.dispatch(frame: Frame) {
    synchronized(lock) {
        when (frame) {
            is Frame.Open -> accept(frame)
            is Frame.Headers -> table.route(frame.streamId)?.onHeaders(frame)
            is Frame.Data -> table.route(frame.streamId)?.onData(frame) ?: frame.payload.release()
            is Frame.WindowUpdate -> table.route(frame.streamId)?.onWindowUpdate(frame.increment)
            is Frame.Reset -> table.route(frame.streamId)?.fail(MuxStreamReset(frame.code))
            else -> Unit // stream-0 frames belong to the handshake (stories 1.6 and 1.7)
        }
    }
}

/** Queues a peer frame for [MuxStream.receive]; [end] closes the inbox behind it. */
internal fun MuxStream.deliver(frame: Frame, end: Boolean) {
    if (inbox.trySend(frame).isFailure) frame.releasePayload()
    if (!end) return
    remoteEnd = true
    inbox.close()
    retireIfDone()
}

private fun MuxStream.onHeaders(frame: Frame.Headers) {
    if (!frame.end) heads++
    if (remoteEnd || heads > MAX_HEADS) reset(ResetCode.PROTOCOL_ERROR) else deliver(frame, frame.end)
}

private fun MuxStream.onData(frame: Frame.Data) {
    val bytes = frame.payload.readableBytes()
    val window = consume(receiveWindow, bytes)
    val misuse = remoteEnd || (bytes == 0 && !frame.end) // an empty non-END DATA costs no credit, so it is refused
    if (misuse || window == null) {
        frame.payload.release()
        reset(if (misuse) ResetCode.PROTOCOL_ERROR else ResetCode.FLOW_CONTROL)
        return
    }
    receiveWindow = window
    unacked += bytes
    deliver(frame, frame.end)
}

private fun MuxStream.onWindowUpdate(increment: UInt) {
    val window = grow(sendWindow, increment)
    when {
        increment == 0u -> reset(ResetCode.PROTOCOL_ERROR)
        window == null -> reset(ResetCode.FLOW_CONTROL)
        else -> {
            sendWindow = window
            credit.trySend(Unit)
        }
    }
}
