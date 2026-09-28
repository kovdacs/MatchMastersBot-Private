package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import org.junit.Assume
import org.junit.Test

/**
 * Live-device HOLD (gridConf≈0.99, boardConf=0, unk≈42) regressions.
 *
 * Root cause class: composed-screen capture includes analyzer UI / overlays →
 * cells UNKNOWN → boardConf penalty clamps to 0. Gates unchanged.
 */
class LiveBoardFrameRegressionTest {

    private val pipeline = VisionPipeline()

    @Test
    fun realFrame_statusBarContentRoi_stillPass() {
        Assume.assumeTrue(RealFrameLoader.resourceExists())
        val frame = RealFrameLoader.loadFromResource()!!
        // Live letterbox often LTRB(0,88,1080,H) from dark status chrome — must not break PASS.
        val roi = ContentRoi(0, 88, frame.width, frame.height)
        val result = pipeline.analyze(frame.pixels, frame.width, frame.height, roi)
        VisionDiagnostics.assertOrDump(
            result,
            result.validation.isPass,
            "status-bar contentRoi must still PASS on pvp_board (do not loosen gates)",
        )
        assertThat(result.gridConfidence).isAtLeast(VisionThresholds.MIN_GRID_CONFIDENCE)
        assertThat(result.boardConfidence).isAtLeast(VisionThresholds.MIN_BOARD_CONFIDENCE)
        assertThat(result.unknownCount).isAtMost(VisionThresholds.MAX_UNKNOWN_COUNT)
        assertThat(VisionThresholds.MIN_GRID_CONFIDENCE).isEqualTo(0.98f)
        assertThat(VisionThresholds.MIN_BOARD_CONFIDENCE).isEqualTo(0.95f)
        assertThat(VisionThresholds.MAX_UNKNOWN_COUNT).isEqualTo(1)
        println(
            "LIVE_ROI statusBar contentRoi=$roi gate=PASS " +
                "gridConf=${"%.4f".format(result.gridConfidence)} " +
                "boardConf=${"%.4f".format(result.boardConfidence)} unk=${result.unknownCount}",
        )
    }

    @Test
    fun grayUiOverlayCoveringBoard_suspectFlag_boardConfNearZero() {
        val (pixels, w, h) = SyntheticFrames.letterboxedBoard(withGutters = true)
        val roi = SyntheticFrames.contentRoiForLetterbox()
        // Keep dark gutters (so PROJECTION gridConf stays high) but paint cell
        // interiors Material-gray — mimics analyzer UI / floating panel over gems.
        val gutter = 2
        val boardSize = roi.width()
        val cell = (boardSize - gutter * 8) / 7
        val boardLeft = roi.left
        val boardTop = roi.top
        val uiGray = PixelMath.rgb(240, 240, 245)
        for (r in 0 until 7) {
            for (c in 0 until 7) {
                val x0 = boardLeft + gutter + c * (cell + gutter)
                val y0 = boardTop + gutter + r * (cell + gutter)
                for (yy in y0 until y0 + cell) {
                    for (xx in x0 until x0 + cell) {
                        if (xx in 0 until w && yy in 0 until h) {
                            pixels[yy * w + xx] = uiGray
                        }
                    }
                }
            }
        }
        val result = pipeline.analyze(pixels, w, h, roi)
        assertThat(result.gridConfidence).isAtLeast(VisionThresholds.MIN_GRID_CONFIDENCE)
        assertThat(result.unknownCount).isAtLeast(VisionPipeline.SUSPECT_OVERLAY_UNKNOWN_MIN)
        assertThat(result.boardConfidence).isLessThan(0.05f)
        assertThat(result.validation.isPass).isFalse()
        assertThat(result.diagnostics["suspectOverlayOrSelfUi"]).isEqualTo("true")
        assertThat(result.diagnostics["boardConfPath"]).isEqualTo("penalty")
        assertThat(result.diagnostics["suspectHint"]).isNotNull()
        // Prefer unknownCount HOLD when unk drives the failure (clearer than boardConf=0 alone).
        val hold = result.validation as ValidationResult.Hold
        assertThat(hold.reason).contains("unknownCount")
        println(
            "OVERLAY_COVER gridConf=${"%.4f".format(result.gridConfidence)} " +
                "boardConf=${"%.4f".format(result.boardConfidence)} unk=${result.unknownCount} " +
                "occ=${result.diagnostics["occSummary"]} reason=${hold.reason}",
        )
    }

    @Test
    fun boardConf_allUnknown_penaltyClampsToZero() {
        // Document formula: unk=42 → penalty 3.36 → coerceIn 0 (not a separate bug).
        val board = VisionBoard.filled(CellVision.unknown(occluded = true, confidence = 0f))
        assertThat(board.unknownCount()).isEqualTo(49)
        val mean = board.meanConfidence()
        assertThat(mean).isEqualTo(0f)
        val unknownPenalty = 49 * 0.08f
        val projected = (mean + 0.05f - unknownPenalty).coerceIn(0f, 1f)
        assertThat(projected).isEqualTo(0f)
    }

    @Test
    fun integrity_gatesUnchanged() {
        assertThat(VisionThresholds.MIN_GRID_CONFIDENCE).isEqualTo(0.98f)
        assertThat(VisionThresholds.MIN_BOARD_CONFIDENCE).isEqualTo(0.95f)
        assertThat(VisionThresholds.MAX_UNKNOWN_COUNT).isEqualTo(1)
    }
}
