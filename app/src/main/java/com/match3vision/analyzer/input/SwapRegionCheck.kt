package com.match3vision.analyzer.input

import com.match3vision.analyzer.moves.Move
import com.match3vision.analyzer.vision.VisionResult

/**
 * Compares the two cells of the attempted swap.
 *
 * A difference here is not proof the gesture caused the swap. A cascade,
 * refill, or overlay can change the same cells. Callers must not promote a
 * region difference to VERIFY SUCCESS.
 */
object SwapRegionCheck {
    fun intendedCellsChanged(
        before: VisionResult,
        after: VisionResult,
        move: Move,
    ): Boolean {
        val beforeA = before.board.get(move.r1, move.c1)
        val beforeB = before.board.get(move.r2, move.c2)
        val afterA = after.board.get(move.r1, move.c1)
        val afterB = after.board.get(move.r2, move.c2)
        return beforeA.color != afterA.color ||
            beforeA.shape != afterA.shape ||
            beforeA.special != afterA.special ||
            beforeB.color != afterB.color ||
            beforeB.shape != afterB.shape ||
            beforeB.special != afterB.special
    }
}
