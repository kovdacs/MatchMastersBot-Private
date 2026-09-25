package com.match3vision.analyzer.rules

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.vision.TileColor

/**
 * Horizontal/vertical runs of 3+ same color.
 * ASSUMPTION: axis-aligned color matches; UNKNOWN never participates.
 */
class MatchDetector {
    data class MatchGroup(
        val cells: List<Pair<Int, Int>>,
        val color: TileColor,
        val horizontal: Boolean,
    ) {
        val size: Int get() = cells.size
    }

    fun findMatches(board: Board): List<MatchGroup> {
        val groups = mutableListOf<MatchGroup>()
        for (r in 0 until Board.SIZE) {
            var c = 0
            while (c < Board.SIZE) {
                val color = board.get(r, c).color
                if (color == TileColor.UNKNOWN) { c++; continue }
                var end = c + 1
                while (end < Board.SIZE && board.get(r, end).color == color) end++
                if (end - c >= 3) {
                    groups += MatchGroup((c until end).map { r to it }, color, true)
                }
                c = end
            }
        }
        for (c in 0 until Board.SIZE) {
            var r = 0
            while (r < Board.SIZE) {
                val color = board.get(r, c).color
                if (color == TileColor.UNKNOWN) { r++; continue }
                var end = r + 1
                while (end < Board.SIZE && board.get(end, c).color == color) end++
                if (end - r >= 3) {
                    groups += MatchGroup((r until end).map { it to c }, color, false)
                }
                r = end
            }
        }
        return groups
    }

    fun matchedCells(board: Board): Set<Pair<Int, Int>> =
        findMatches(board).flatMap { it.cells }.toSet()
}
