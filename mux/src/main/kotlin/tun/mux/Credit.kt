package tun.mux

// AD-3: credit accounting and buffer release are plain functions. Windows are Longs so u32 arithmetic cannot wrap.

internal const val MAX_WINDOW = 0xFFFF_FFFFL

/** The send window after the peer returns [increment] bytes, or `null` past 2^32-1 (a flow-control error). */
internal fun grow(window: Long, increment: UInt): Long? = (window + increment.toLong()).takeIf { it <= MAX_WINDOW }

/** The receive window after [bytes] of DATA arrive, or `null` when they exceed it (a flow-control error). */
internal fun consume(window: Long, bytes: Int): Long? = (window - bytes).takeIf { it >= 0 }

/** Payload bytes the next DATA frame carries, of [pending] bytes still to send. */
internal fun chunk(window: Long, maxFrame: UInt, pending: Int): Int =
    minOf(window, maxFrame.toLong(), pending.toLong()).toInt()

/** The id after [last], or a failure once u32 ids are used up: ids are never reused. */
internal fun nextStreamId(last: UInt): UInt {
    check(last < UInt.MAX_VALUE) { "stream ids exhausted" }
    return last + 1u
}

internal fun Frame.releasePayload() {
    if (this is Frame.Data) payload.release()
}
