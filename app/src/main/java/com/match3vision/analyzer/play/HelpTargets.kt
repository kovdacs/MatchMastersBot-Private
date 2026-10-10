package com.match3vision.analyzer.play

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.vision.TileColor

/**
 * Box turns the tapped gem into a rainbow color bomb.
 * The tap lands on a cell beside a pair, so the bomb sits next to a cluster.
 */
object HelpTargets {
    fun adjacentToCluster(board: Board): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bestRun = 0
        fun consider(row: Int, col: Int, run: Int) {
            if (row !in 0 until Board.SIZE || col !in 0 until Board.SIZE) return
            val tile = board.get(row, col)
            if (!tile.visible || tile.color == TileColor.UNKNOWN) return
            if (run > bestRun) {
                bestRun = run
                best = row to col
            }
        }
        for (row in 0 until Board.SIZE) {
            var col = 0
            while (col < Board.SIZE) {
                val color = colorAt(board, row, col)
                if (color == null) {
                    col++
                } else {
                    var end = col + 1
                    while (end < Board.SIZE && colorAt(board, row, end) == color) end++
                    val run = end - col
                    if (run >= 2) {
                        consider(row, col - 1, run)
                        consider(row, end, run)
                    }
                    col = end
                }
            }
        }
        for (col in 0 until Board.SIZE) {
            var row = 0
            while (row < Board.SIZE) {
                val color = colorAt(board, row, col)
                if (color == null) {
                    row++
                } else {
                    var end = row + 1
                    while (end < Board.SIZE && colorAt(board, end, col) == color) end++
                    val run = end - row
                    if (run >= 2) {
                        consider(row - 1, col, run)
                        consider(end, col, run)
                    }
                    row = end
                }
            }
        }
        return best
    }

    private fun colorAt(board: Board, row: Int, col: Int): TileColor? {
        if (row !in 0 until Board.SIZE || col !in 0 until Board.SIZE) return null
        val tile = board.get(row, col)
        if (!tile.visible || tile.color == TileColor.UNKNOWN) return null
        return tile.color
    }
}
