package com.match3vision.analyzer.input

/**
 * Stability, not wall-clock age. Two consecutive PASS frames with equal
 * labels may be swiped when both were captured at least [POST_GESTURE_MS]
 * after the last gesture and the newer frame is at most [MAX_FRAME_AGE_MS] old.
 * The caller still re-checks the two swapped cells on the newest frame.
 */
object SwipeGuard {
    const val MAX_FRAME_AGE_MS = 5_000L
    const val POST_GESTURE_MS = 2_500L

    /**
     * Null when this frame may be swiped. The first call only records the
     * label. [capturedAtMs] and [previousCapturedAtMs] are wall-clock capture
     * times. [Long.MAX_VALUE] skips the post-gesture check for that frame.
     */
    fun motionBlock(
        ageMs: Long,
        previousLabel: Long?,
        labelHash: Long,
        capturedAtMs: Long = Long.MAX_VALUE,
        previousCapturedAtMs: Long = Long.MAX_VALUE,
        lastGestureAtMs: Long = 0L,
    ): String? {
        if (ageMs > MAX_FRAME_AGE_MS) return "frame older than ${MAX_FRAME_AGE_MS}ms"
        if (previousLabel == null) return "waiting for the previous PASS frame"
        if (previousLabel != labelHash) return "labels changed"
        if (lastGestureAtMs > 0L) {
            val readyAt = lastGestureAtMs + POST_GESTURE_MS
            if (capturedAtMs < readyAt || previousCapturedAtMs < readyAt) {
                return "waiting for the board to settle"
            }
        }
        return null
    }

    fun cellsMatch(
        planned: LongArray,
        newest: LongArray,
        r1: Int,
        c1: Int,
        r2: Int,
        c2: Int,
    ): Boolean {
        if (planned.size != newest.size) return false
        fun at(keys: LongArray, row: Int, col: Int): Long = keys[row * 7 + col]
        return at(planned, r1, c1) == at(newest, r1, c1) &&
            at(planned, r2, c2) == at(newest, r2, c2)
    }

    /** Why a swipe that was sent did not verify. */
    fun unverifiedReason(ageMs: Long, labelsMatched: Boolean, onCellCenter: Boolean): String = when {
        ageMs > MAX_FRAME_AGE_MS || !labelsMatched -> "swipe made during board motion"
        !onCellCenter -> "swipe landed off-cell"
        else -> "label error"
    }
}
