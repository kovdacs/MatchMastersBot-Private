package com.match3vision.analyzer.input

/**
 * Two consecutive PASS frames with equal labels may be swiped when the newer
 * frame is at most [MAX_FRAME_AGE_MS] old. When both capture times are known,
 * they must be at least [MIN_AGREE_GAP_MS] apart. There is no wait after the
 * previous gesture. The caller still re-checks the two swapped cells.
 */
object SwipeGuard {
    const val MAX_FRAME_AGE_MS = 5_000L
    const val MIN_AGREE_GAP_MS = 250L

    /**
     * Null when this frame may be swiped. The first call only records the
     * label. [Long.MAX_VALUE] means that capture time is unknown, so the gap
     * check is skipped for that pair.
     */
    @Suppress("UNUSED_PARAMETER")
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
        if (capturedAtMs != Long.MAX_VALUE && previousCapturedAtMs != Long.MAX_VALUE) {
            if (capturedAtMs - previousCapturedAtMs < MIN_AGREE_GAP_MS) {
                return "frames too close"
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
