package com.match3vision.analyzer.input

/**
 * A swipe is sent only from a fresh frame whose labels still match the
 * previous PASS frame, and only after the two swapped cells still match on
 * the newest frame. Anything else is board motion or a misread.
 */
object SwipeGuard {
    const val MAX_FRAME_AGE_MS = 1_500L

    /**
     * Null when this frame may be swiped. The first call only records the
     * label; a swipe needs that previous PASS frame to agree.
     */
    fun motionBlock(ageMs: Long, previousLabel: Long?, labelHash: Long): String? {
        if (ageMs > MAX_FRAME_AGE_MS) return "swipe made during board motion"
        if (previousLabel == null) return "waiting for the previous PASS frame"
        if (previousLabel != labelHash) return "swipe made during board motion"
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
