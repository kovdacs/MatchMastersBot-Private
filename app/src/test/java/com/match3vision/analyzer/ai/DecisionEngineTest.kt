package com.match3vision.analyzer.ai

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.board.GameState
import com.match3vision.analyzer.board.VisionState
import com.match3vision.analyzer.vision.TileColor
import org.junit.Test

class DecisionEngineTest {
    private fun puzzle(): Board {
        val colors = Array(7) { r -> Array(7) { c ->
            listOf(TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O, TileColor.R)[(r * 2 + c) % 6]
        } }
        colors[0][0] = TileColor.R; colors[0][1] = TileColor.R; colors[0][2] = TileColor.B; colors[0][3] = TileColor.R
        colors[1][2] = TileColor.R
        return Board.fromColors(colors)
    }

    @Test fun holdGate_blocks() {
        val state = GameState(board = puzzle(), vision = VisionState(gatePass = false, holdReason = "test hold"))
        val r = DecisionEngine().decide(state, useLookahead = false)
        assertThat(r.blocked).isTrue()
        assertThat(r.topMoves).isEmpty()
    }

    @Test fun passGate_returnsMoves() {
        val state = GameState(
            board = puzzle(),
            vision = VisionState(gatePass = true, boardConfidence = 0.97f, gridConfidence = 0.99f, unknownCount = 0),
        )
        val r = DecisionEngine().decide(state, useLookahead = false, topN = 5)
        assertThat(r.blocked).isFalse()
        assertThat(r.topMoves).isNotEmpty()
        assertThat(r.topMoves.size).isAtMost(5)
    }
}
