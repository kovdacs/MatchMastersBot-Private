package com.match3vision.analyzer.play

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.moves.PlayMoveRanker
import com.match3vision.analyzer.rules.GravityEngine
import com.match3vision.analyzer.rules.MatchDetector
import com.match3vision.analyzer.vision.SpecialType
import com.match3vision.analyzer.vision.TileColor

/**
 * Box turns the tapped gem into a rainbow color bomb.
 * The tap lands on a cell beside a pair, so the bomb sits next to a cluster.
 */
object HelpTargets {
    data class HammerTarget(val row: Int, val col: Int, val blue: Int, val total: Int)

    private var cachedHash: Long? = null
    private var cachedTarget: HammerTarget? = null

    /** Same board as last call: reuse the result (the simulation runs once per board). */
    @Synchronized
    fun hammerForExtraMoveCached(board: Board): HammerTarget? {
        val hash = board.contentHash()
        if (cachedHash == hash) return cachedTarget
        val target = hammerForExtraMove(board)
        cachedHash = hash
        cachedTarget = target
        return target
    }

    /**
     * Hammer removes one gem; the column above falls by one and an unknown gem
     * enters at the top. Returns the visible, known-color, non-special cell
     * whose removal leaves an extra-move swap (initial 4+ match on known cells,
     * not touching the unknown refill cell),
     * best blue then total. Null when the board has any hidden/unknown cell,
     * when the removal itself would cascade (unpredictable), or nothing helps.
     */
    fun hammerForExtraMove(
        board: Board,
        ranker: PlayMoveRanker = PlayMoveRanker(),
        gravity: GravityEngine = GravityEngine(),
        detector: MatchDetector = MatchDetector(),
    ): HammerTarget? {
        for (row in 0 until Board.SIZE) {
            for (col in 0 until Board.SIZE) {
                val t = board.get(row, col)
                if (!t.visible || (t.color == TileColor.UNKNOWN && t.special == SpecialType.NONE)) return null
            }
        }
        var best: HammerTarget? = null
        for (row in 0 until Board.SIZE) {
            for (col in 0 until Board.SIZE) {
                val tile = board.get(row, col)
                if (tile.color == TileColor.UNKNOWN || tile.special != SpecialType.NONE) continue
                val fallen = gravity.apply(gravity.clearCells(board, setOf(row to col))).board
                if (detector.findMatches(fallen).isNotEmpty()) continue
                val extra = ranker.rank(fallen).ordered
                    // The refilled top cell is unknown, so the ranker marks every
                    // candidate uncertain; the extra-move flag is the swap's own
                    // initial match on known cells and stays reliable.
                    .filter { m -> m.extraMove && !(m.move.r1 == 0 && m.move.c1 == col) && !(m.move.r2 == 0 && m.move.c2 == col) }
                    .maxWithOrNull(compareBy({ it.blueCleared }, { it.totalCleared }))
                    ?: continue
                val cand = HammerTarget(row, col, extra.blueCleared, extra.totalCleared)
                val b = best
                if (b == null || cand.blue > b.blue || (cand.blue == b.blue && cand.total > b.total)) {
                    best = cand
                }
            }
        }
        return best
    }

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
