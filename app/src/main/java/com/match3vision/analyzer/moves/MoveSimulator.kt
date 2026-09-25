package com.match3vision.analyzer.moves

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.rules.CascadeEngine
import com.match3vision.analyzer.rules.MoveResolver

class MoveSimulator(
    private val resolver: MoveResolver = MoveResolver(),
) {
    data class Simulation(
        val move: Move,
        val legal: Boolean,
        val uncertain: Boolean,
        val boardAfter: Board,
        val clearedCells: Int,
        val cascadeSteps: Int,
        val confidencePenalty: Float,
    )

    fun simulate(board: Board, move: Move): Simulation {
        val resolved = resolver.resolve(board, move.r1, move.c1, move.r2, move.c2)
        val penalty = when {
            resolved.uncertain -> 0.35f
            board.unknownCount() > 0 -> 0.15f * board.unknownCount().coerceAtMost(5)
            else -> 0f
        }
        return Simulation(
            move = move,
            legal = resolved.legal,
            uncertain = resolved.uncertain,
            boardAfter = resolved.boardAfter,
            clearedCells = resolved.cascade?.clearedCells ?: 0,
            cascadeSteps = resolved.cascade?.steps ?: 0,
            confidencePenalty = penalty.coerceIn(0f, 1f),
        )
    }
}

class CascadeSimulator(
    private val cascadeEngine: CascadeEngine = CascadeEngine(doRefill = false),
) {
    fun simulate(board: Board) = cascadeEngine.run(board)
}

class FutureBoardGenerator(
    private val moveSimulator: MoveSimulator = MoveSimulator(),
) {
    fun afterMove(board: Board, move: Move): Board =
        moveSimulator.simulate(board, move).boardAfter
}

class MoveValidator(
    private val generator: LegalMoveGenerator = LegalMoveGenerator(),
) {
    fun isLegal(board: Board, move: Move): Boolean =
        generator.generate(board).any { it.normalized() == move.normalized() }
}
