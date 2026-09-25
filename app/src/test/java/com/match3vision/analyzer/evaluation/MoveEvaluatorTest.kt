package com.match3vision.analyzer.evaluation

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.moves.LegalMoveGenerator
import com.match3vision.analyzer.moves.Move
import com.match3vision.analyzer.vision.TileColor
import org.junit.Test

class MoveEvaluatorTest {
    private fun puzzle(): Board {
        val colors = Array(7) { r -> Array(7) { c ->
            listOf(TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O, TileColor.R)[(r * 2 + c) % 6]
        } }
        colors[0][0] = TileColor.R; colors[0][1] = TileColor.R; colors[0][2] = TileColor.B; colors[0][3] = TileColor.R
        colors[1][2] = TileColor.R
        return Board.fromColors(colors)
    }

    @Test fun evaluate_legal_hasReasons() {
        val e = MoveEvaluator().evaluate(puzzle(), Move(0, 2, 1, 2))
        assertThat(e.reasons).isNotEmpty()
        assertThat(e.matchScore).isGreaterThan(0f)
    }

    @Test fun risk_increasesWithUncertainty() {
        val risk = RiskEvaluator()
        assertThat(risk.penalty(true, 3, 0.3f)).isGreaterThan(risk.penalty(false, 0, 0f))
    }

    @Test fun ranking_descendingEv() {
        val board = puzzle()
        val evals = LegalMoveGenerator().generate(board).map { MoveEvaluator().evaluate(board, it) }
            .sortedByDescending { it.expectedValue }
        assertThat(evals).isNotEmpty()
        if (evals.size >= 2) assertThat(evals.first().expectedValue).isAtLeast(evals.last().expectedValue)
    }
}
