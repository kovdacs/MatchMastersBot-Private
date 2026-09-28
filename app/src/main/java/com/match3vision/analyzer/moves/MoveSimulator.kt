package com.match3vision.analyzer.moves

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.rules.CascadeEngine
import com.match3vision.analyzer.rules.MatchDetector
import com.match3vision.analyzer.rules.MoveResolver
import com.match3vision.analyzer.vision.SpecialType

class MoveSimulator(
    private val resolver: MoveResolver = MoveResolver(),
    private val matchDetector: MatchDetector = MatchDetector(),
) {
    data class Simulation(
        val move: Move,
        val legal: Boolean,
        val uncertain: Boolean,
        val boardAfter: Board,
        val clearedCells: Int,
        val cascadeSteps: Int,
        val confidencePenalty: Float,
        val matchCount: Int = 0,
        val maxMatchSize: Int = 0,
        val concurrentMatches: Int = 0,
        val specialsCreated: Int = 0,
        val existingSpecialActivated: Boolean = false,
        val extraMoveLikely: Boolean = false,
    )

    fun simulate(board: Board, move: Move): Simulation {
        val resolved = resolver.resolve(board, move.r1, move.c1, move.r2, move.c2)
        val penalty = when {
            resolved.uncertain -> 0.35f
            board.unknownCount() > 0 -> 0.15f * board.unknownCount().coerceAtMost(5)
            else -> 0f
        }
        val cascade = resolved.cascade
        val swapped = if (resolved.legal) board.swapCopy(move.r1, move.c1, move.r2, move.c2) else board
        val firstMatches = if (resolved.legal) matchDetector.findMatches(swapped) else emptyList()
        val t1 = board.get(move.r1, move.c1)
        val t2 = board.get(move.r2, move.c2)
        val specialActivated = resolved.legal &&
            (t1.special != SpecialType.NONE || t2.special != SpecialType.NONE || resolved.combo != null)
        return Simulation(
            move = move,
            legal = resolved.legal,
            uncertain = resolved.uncertain,
            boardAfter = resolved.boardAfter,
            clearedCells = cascade?.clearedCells ?: 0,
            cascadeSteps = cascade?.steps ?: 0,
            confidencePenalty = penalty.coerceIn(0f, 1f),
            matchCount = cascade?.matchGroupsTotal ?: firstMatches.size,
            maxMatchSize = cascade?.maxMatchSize
                ?: firstMatches.maxOfOrNull { it.size } ?: 0,
            concurrentMatches = cascade?.concurrentMatchesFirstStep
                ?: firstMatches.size,
            specialsCreated = cascade?.specialsCreated ?: 0,
            existingSpecialActivated = specialActivated,
            extraMoveLikely = cascade?.extraMoveLikely == true,
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
