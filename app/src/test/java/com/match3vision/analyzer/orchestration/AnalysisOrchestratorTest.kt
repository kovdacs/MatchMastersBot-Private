package com.match3vision.analyzer.orchestration

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import com.match3vision.analyzer.vision.CellVision
import com.match3vision.analyzer.vision.GridGeometry
import com.match3vision.analyzer.vision.GridMethod
import com.match3vision.analyzer.vision.SpecialType
import com.match3vision.analyzer.vision.TileColor
import com.match3vision.analyzer.vision.TileShape
import com.match3vision.analyzer.vision.ValidationResult
import com.match3vision.analyzer.vision.VisionBoard
import com.match3vision.analyzer.vision.VisionResult
import com.match3vision.analyzer.vision.VisionThresholds
import org.junit.Test

class AnalysisOrchestratorTest {

    private fun cell(color: TileColor) = CellVision(
        color = color,
        shape = when (color) {
            TileColor.R -> TileShape.CIRCLE
            TileColor.B -> TileShape.STAR
            TileColor.Y -> TileShape.TRIANGLE
            TileColor.G -> TileShape.DIAMOND
            TileColor.P -> TileShape.SQUARE
            TileColor.O -> TileShape.HEX
            TileColor.UNKNOWN -> TileShape.UNKNOWN
        },
        special = SpecialType.NONE,
        occluded = false,
        confidence = 1f,
        isUnknown = color == TileColor.UNKNOWN,
    )

    private fun visionFromColors(
        colors: Array<Array<TileColor>>,
        gate: ValidationResult,
        boardConf: Float,
        gridConf: Float,
    ): VisionResult {
        val cells = Array(7) { r -> Array(7) { c -> cell(colors[r][c]) } }
        val unk = colors.sumOf { row -> row.count { it == TileColor.UNKNOWN } }
        val roi = ContentRoi.full(700, 700)
        return VisionResult(
            board = VisionBoard(cells),
            grid = GridGeometry.evenSplit(roi, confidence = gridConf),
            unknownCount = unk,
            confidence = boardConf,
            boardConfidence = boardConf,
            gridConfidence = gridConf,
            validation = gate,
            method = GridMethod.EVEN_SPLIT,
        )
    }

    @Test
    fun holdVision_blocksMoveAnalysis() {
        val colors = Array(7) { r ->
            Array(7) { c -> if ((r + c) % 2 == 0) TileColor.R else TileColor.B }
        }
        val vision = visionFromColors(
            colors,
            ValidationResult.Hold("HOLD — Decision AI blocked"),
            boardConf = 0.5f,
            gridConf = 0.5f,
        )
        val snap = AnalysisOrchestrator().analyzeVisionResult(vision, timestampMs = 1_000L)
        assertThat(snap.decisionBlocked).isTrue()
        assertThat(snap.topMovesLines).isEmpty()
        assertThat(snap.holdReason!!.lowercase()).contains("hold")
    }

    @Test
    fun passVision_optionalMoveAnalysis_runsReadOnly() {
        val colors = Array(7) { r ->
            Array(7) { c ->
                listOf(
                    TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O, TileColor.R,
                )[(r * 3 + c * 2) % 6]
            }
        }
        colors[0][0] = TileColor.R
        colors[0][1] = TileColor.R
        colors[0][2] = TileColor.B
        colors[0][3] = TileColor.Y
        colors[1][2] = TileColor.R
        val vision = visionFromColors(
            colors,
            ValidationResult.Pass,
            boardConf = VisionThresholds.MIN_BOARD_CONFIDENCE,
            gridConf = VisionThresholds.MIN_GRID_CONFIDENCE,
        )
        val snap = AnalysisOrchestrator().analyzeVisionResult(vision, timestampMs = 2_000L)
        assertThat(snap.decisionBlocked).isFalse()
        assertThat(snap.topMovesLines).isNotEmpty()
        assertThat(snap.topMovesLines.size).isAtMost(5)
        assertThat(snap.gameStateText).contains("MoveAnalysisEngine V1")
        assertThat(snap.whyText).isNotEmpty()
    }
}
