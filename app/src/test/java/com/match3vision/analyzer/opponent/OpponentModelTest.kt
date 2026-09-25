package com.match3vision.analyzer.opponent

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.board.OpponentState
import com.match3vision.analyzer.vision.TileColor
import org.junit.Test

class OpponentModelTest {
    private fun board() = Board.fromColors(Array(7) { r -> Array(7) { c ->
        listOf(TileColor.B, TileColor.R, TileColor.Y, TileColor.G, TileColor.P, TileColor.O)[(r + c) % 6]
    } })

    @Test fun tracker_defaultUnknown() {
        assertThat(OpponentStateTracker().current().observable).isFalse()
    }

    @Test fun moveDetector_changeUnknownActor() {
        val d = OpponentMoveDetector()
        val b1 = board()
        d.observe(b1, 1)
        val b2 = b1.setCopy(0, 0, b1.get(0, 0).copy(color = TileColor.O))
        val det = d.observe(b2, 2)
        assertThat(det.changed).isTrue()
        assertThat(det.unknown).isTrue()
    }

    @Test fun threat_unknownWithoutHud() {
        assertThat(OpponentThreatEvaluator().evaluate(OpponentState.unknown(), board()).unknown).isTrue()
    }

    @Test fun response_unknownPrior() {
        val prior = OpponentResponseModel().prior(
            OpponentThreatEvaluator.Threat(0f, true, emptyList()),
        )
        assertThat(prior.unknown).isTrue()
    }
}
