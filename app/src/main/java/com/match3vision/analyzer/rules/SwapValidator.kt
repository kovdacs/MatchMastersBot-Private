package com.match3vision.analyzer.rules

import com.match3vision.analyzer.board.Board
import kotlin.math.abs

class SwapValidator(
    private val matchDetector: MatchDetector = MatchDetector(),
) {
    data class SwapCheck(val legal: Boolean, val uncertain: Boolean, val reason: String)

    fun isAdjacent(r1: Int, c1: Int, r2: Int, c2: Int): Boolean =
        abs(r1 - r2) + abs(c1 - c2) == 1

    fun validate(board: Board, r1: Int, c1: Int, r2: Int, c2: Int): SwapCheck {
        if (!isAdjacent(r1, c1, r2, c2)) return SwapCheck(false, false, "not adjacent")
        val a = board.get(r1, c1)
        val b = board.get(r2, c2)
        if (a.isUnknown || b.isUnknown) return SwapCheck(false, true, "UNKNOWN cell — simulation uncertain")
        if (a.locked || b.locked) return SwapCheck(false, false, "locked cell")
        val swapped = board.swapCopy(r1, c1, r2, c2)
        if (matchDetector.findMatches(swapped).isEmpty()) return SwapCheck(false, false, "no match created")
        return SwapCheck(true, false, "ok")
    }
}
