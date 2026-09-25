package com.match3vision.analyzer.safety

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.board.GameState
import com.match3vision.analyzer.board.VisionState
import com.match3vision.analyzer.vision.TileColor
import org.junit.Test

class SafetyGateTest {
    private fun okState() = GameState(
        board = Board.fromColors(Array(7) { r -> Array(7) { c ->
            listOf(TileColor.B, TileColor.R, TileColor.Y, TileColor.G, TileColor.P, TileColor.O)[(r + c) % 6]
        } }),
        vision = VisionState(gatePass = true, boardConfidence = 0.99f, gridConfidence = 0.99f, unknownCount = 0),
    )

    @Test fun emergencyStop_blocks() {
        val g = SafetyGate()
        g.engageEmergencyStop()
        assertThat(g.check(okState(), 1000L).allowDecision).isFalse()
    }

    @Test fun visionHold_blocks() {
        val g = SafetyGate()
        val s = okState().copy(vision = VisionState(gatePass = false, holdReason = "x"))
        assertThat(g.check(s, 1000L).allowDecision).isFalse()
    }

    @Test fun duplicateFrame_blocks() {
        val g = SafetyGate()
        val s = okState()
        assertThat(g.check(s, 1000L).allowDecision).isTrue()
        assertThat(g.check(s, 2000L).allowDecision).isFalse()
    }
}
