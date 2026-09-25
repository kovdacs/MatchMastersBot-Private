package com.match3vision.analyzer.rules

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.board.Tile
import com.match3vision.analyzer.vision.SpecialType
import com.match3vision.analyzer.vision.TileColor
import com.match3vision.analyzer.vision.TileShape

/** Gravity: tiles fall down (increasing row). ASSUMPTION: column gravity. */
class GravityEngine {
    data class GravityResult(val board: Board, val moved: Boolean)

    fun apply(board: Board): GravityResult {
        val grid = board.toMutableGrid()
        var moved = false
        for (c in 0 until Board.SIZE) {
            val stack = mutableListOf<Tile>()
            for (r in 0 until Board.SIZE) {
                val t = grid[r][c]
                if (t.visible) stack.add(t)
            }
            val empties = Board.SIZE - stack.size
            for (r in 0 until Board.SIZE) {
                val newTile = if (r < empties) {
                    emptyTile(r, c)
                } else {
                    stack[r - empties].copy(row = r, col = c)
                }
                if (newTile.color != grid[r][c].color || newTile.visible != grid[r][c].visible) moved = true
                grid[r][c] = newTile
            }
        }
        return GravityResult(Board.fromGrid(grid), moved)
    }

    fun clearCells(board: Board, cells: Set<Pair<Int, Int>>): Board {
        var b = board
        for ((r, c) in cells) b = b.setCopy(r, c, emptyTile(r, c))
        return b
    }

    companion object {
        fun emptyTile(row: Int, col: Int) = Tile(
            row = row, col = col,
            color = TileColor.UNKNOWN, shape = TileShape.UNKNOWN,
            special = SpecialType.NONE, confidence = 0f, visible = false,
        )
    }
}
