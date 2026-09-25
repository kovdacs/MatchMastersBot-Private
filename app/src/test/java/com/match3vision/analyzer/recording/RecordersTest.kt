package com.match3vision.analyzer.recording

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.vision.TileColor
import org.junit.Test

class RecordersTest {
    @Test fun frameAndDecisionExport() {
        val frames = FrameRecorder()
        frames.record(1L, 1080, 1920, 42L)
        assertThat(frames.exportJson()).contains("1080")
        val boards = BoardRecorder()
        val b = Board.fromColors(Array(7) { r -> Array(7) { c ->
            listOf(TileColor.B, TileColor.R, TileColor.Y, TileColor.G, TileColor.P, TileColor.O)[(r + c) % 6]
        } })
        boards.record(1L, b, 0, "{}")
        assertThat(boards.exportJson()).contains("hash")
        val decisions = DecisionRecorder()
        decisions.record(1L, true, emptyList(), "hold")
        assertThat(decisions.exportJson()).contains("blocked")
    }
}
