package com.match3vision.analyzer.input

import com.match3vision.analyzer.moves.Move
import com.match3vision.analyzer.vision.GridGeometry

/**
 * Converts a board [Move] into screen swipe coordinates using **recognized**
 * [GridGeometry] cell centers. No hardcoded pvp_board / device pixel literals.
 */
class TouchCoordinateMapper(
    private val swipeDurationMs: Long = InputThresholds.SWIPE_DURATION_MS,
) {
    fun toGesture(move: Move, grid: GridGeometry): GestureSpec {
        require(grid.isMonotonic()) { "grid boundaries must be monotonic" }
        val from = grid.cellBox(move.r1, move.c1)
        val to = grid.cellBox(move.r2, move.c2)
        return GestureSpec(
            startX = from.centerX(),
            startY = from.centerY(),
            endX = to.centerX(),
            endY = to.centerY(),
            durationMs = swipeDurationMs,
        )
    }
}
