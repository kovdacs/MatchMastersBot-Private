package com.match3vision.analyzer.rules

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.board.Tile
import com.match3vision.analyzer.vision.SpecialType
import com.match3vision.analyzer.vision.TileColor

/**
 * Refill empty cells. Without provider → UNKNOWN (uncertain). Never invent colors.
 */
class BoardRefill(
    private val colorProvider: ((row: Int, col: Int) -> TileColor)? = null,
) {
    data class RefillResult(val board: Board, val uncertain: Boolean, val filled: Int)

    fun refill(board: Board): RefillResult {
        var filled = 0
        var uncertain = false
        val grid = board.toMutableGrid()
        for (r in 0 until Board.SIZE) for (c in 0 until Board.SIZE) {
            if (!grid[r][c].visible) {
                val color = colorProvider?.invoke(r, c) ?: TileColor.UNKNOWN
                if (color == TileColor.UNKNOWN) uncertain = true
                grid[r][c] = Tile(
                    row = r, col = c, color = color,
                    shape = Board.shapeFor(color), special = SpecialType.NONE,
                    confidence = if (color == TileColor.UNKNOWN) 0f else 0.5f,
                    visible = true,
                )
                filled++
            }
        }
        return RefillResult(Board.fromGrid(grid), uncertain, filled)
    }
}
