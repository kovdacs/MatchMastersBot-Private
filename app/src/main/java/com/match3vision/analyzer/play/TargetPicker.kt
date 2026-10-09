package com.match3vision.analyzer.play

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.rules.GravityEngine
import com.match3vision.analyzer.rules.MatchDetector
import com.match3vision.analyzer.vision.SpecialType
import com.match3vision.analyzer.vision.TileColor

/**
 * Picks a booster or hammer target by clearing the candidate and counting
 * blue, then total, after gravity and cascades. This does not dispatch.
 */
class TargetPicker(
    private val detector: MatchDetector = MatchDetector(),
    private val gravity: GravityEngine = GravityEngine(),
) {
    data class Target(val row: Int, val col: Int, val blue: Int, val total: Int, val targetType: String)

    fun best(board: Board, targetType: String): Target = when (targetType) {
        "color" -> bestColor(board)
        "row" -> bestLine(board, row = true)
        "col" -> bestLine(board, row = false)
        else -> bestCell(board)
    }

    fun bestCell(board: Board): Target {
        var best = Target(0, 0, -1, -1, "cell")
        for (row in 0 until Board.SIZE) {
            for (col in 0 until Board.SIZE) {
                val tile = board.get(row, col)
                if (!tile.visible || tile.color == TileColor.UNKNOWN) continue
                val score = score(board, setOf(row to col))
                if (better(score, best)) best = Target(row, col, score.first, score.second, "cell")
            }
        }
        return best
    }

    private fun bestColor(board: Board): Target {
        var best = Target(0, 0, -1, -1, "color")
        for (color in TileColor.entries) {
            if (color == TileColor.UNKNOWN) continue
            val cells = ArrayList<Pair<Int, Int>>()
            board.forEachTile { tile ->
                if (tile.visible && tile.color == color) cells += tile.row to tile.col
            }
            if (cells.isEmpty()) continue
            val score = score(board, cells.toSet())
            if (better(score, best)) {
                val anchor = cells.first()
                best = Target(anchor.first, anchor.second, score.first, score.second, "color")
            }
        }
        return best
    }

    private fun bestLine(board: Board, row: Boolean): Target {
        var best = Target(0, 0, -1, -1, if (row) "row" else "col")
        for (index in 0 until Board.SIZE) {
            val cells = (0 until Board.SIZE).map { if (row) index to it else it to index }.toSet()
            val score = score(board, cells)
            if (better(score, best)) best = Target(index, index, score.first, score.second, best.targetType)
        }
        return best
    }

    private fun better(score: Pair<Int, Int>, best: Target): Boolean =
        score.first > best.blue || (score.first == best.blue && score.second > best.total)

    private fun score(board: Board, seed: Set<Pair<Int, Int>>): Pair<Int, Int> {
        val cells = HashSet(seed)
        for ((row, col) in seed) {
            val tile = board.get(row, col)
            if (tile.special == SpecialType.NONE) continue
            addSpecial(cells, board, row, col, tile.special, tile.starValue)
        }
        var current = gravity.apply(gravity.clearCells(board, cells)).board
        var blue = 0
        var total = 0
        var steps = 0
        while (steps < 6) {
            val matches = detector.findMatches(current)
            if (matches.isEmpty()) break
            val matched = matches.flatMap { it.cells }.toSet()
            for ((row, col) in matched) {
                if (current.get(row, col).color == TileColor.B) blue++
                total++
            }
            current = gravity.apply(gravity.clearCells(current, matched)).board
            steps++
        }
        for ((row, col) in cells) {
            val tile = board.get(row, col)
            if (!tile.visible) continue
            if (tile.color == TileColor.B) blue++
            total++
        }
        return blue to total
    }

    private fun addSpecial(
        cells: MutableSet<Pair<Int, Int>>,
        board: Board,
        row: Int,
        col: Int,
        special: SpecialType,
        axis: Int,
    ) {
        when (special) {
            SpecialType.TWO_WAY_ARROW -> if (axis == SpecialLabels.AXIS_COL) {
                for (r in 0 until Board.SIZE) cells += r to col
            } else {
                for (c in 0 until Board.SIZE) cells += row to c
            }
            SpecialType.BOMB -> {
                for (r in (row - 1)..(row + 1)) {
                    for (c in (col - 1)..(col + 1)) {
                        if (r in 0 until Board.SIZE && c in 0 until Board.SIZE) cells += r to c
                    }
                }
            }
            SpecialType.LIGHTNING -> {
                val color = board.get(row, col).color
                if (color == TileColor.UNKNOWN) {
                    cells += row to col
                } else {
                    for (r in 0 until Board.SIZE) {
                        for (c in 0 until Board.SIZE) {
                            if (board.get(r, c).color == color) cells += r to c
                        }
                    }
                }
            }
            SpecialType.NONE -> Unit
        }
    }
}
