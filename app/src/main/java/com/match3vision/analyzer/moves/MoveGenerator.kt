package com.match3vision.analyzer.moves

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.rules.SwapValidator

/** All legal adjacent swaps; reproducible order. UNKNOWN excluded (fail-closed). */
class LegalMoveGenerator(
    private val swapValidator: SwapValidator = SwapValidator(),
    private val excludeUnknown: Boolean = true,
) {
    fun generate(board: Board): List<Move> {
        val moves = linkedSetOf<Move>()
        val deltas = listOf(0 to 1, 1 to 0)
        for (r in 0 until Board.SIZE) {
            for (c in 0 until Board.SIZE) {
                for ((dr, dc) in deltas) {
                    val r2 = r + dr
                    val c2 = c + dc
                    if (r2 !in 0 until Board.SIZE || c2 !in 0 until Board.SIZE) continue
                    val a = board.get(r, c)
                    val b = board.get(r2, c2)
                    if (excludeUnknown && (a.isUnknown || b.isUnknown)) continue
                    val check = swapValidator.validate(board, r, c, r2, c2)
                    if (check.legal && !(excludeUnknown && check.uncertain)) {
                        moves += Move(r, c, r2, c2).normalized()
                    }
                }
            }
        }
        return moves.toList()
    }
}

typealias MoveGenerator = LegalMoveGenerator
