package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import org.junit.Assume
import org.junit.Test

/**
 * Live-device HOLD regressions.
 *
 * 1) Self-UI / overlay: gridConf≈0.99, boardConf=0, unk≈42 (composed capture).
 * 2) Mild boardRoi mis-snap (bubble / side-chrome): gridConf≈0.972 < 0.98 while
 *    board is clear — soft gutter re-pick must lift ≥ MIN_GRID without loosening gates
 *    or changing the clean REAL_FRAME first-pass (golden 0.9872).
 * 3) Live cell HOLD unk=2: purple square / mushroom misread as STAR at high conf —
 *    dominance reconcile must keep unk≤1 on clear PvP without loosening MAX_UNKNOWN.
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
    fun realFrame_leftMisSnapLikeLive_softRecoverClearsMinGrid() {
        Assume.assumeTrue(RealFrameLoader.resourceExists())
        val frame = RealFrameLoader.loadFromResource()!!
        assertThat(VisionThresholds.MIN_GRID_CONFIDENCE).isEqualTo(0.98f)

        // Full-frame REAL_FRAME still first-pass PASS (no recovery) — golden intact.
        val full = pipeline.analyze(
            frame.pixels, frame.width, frame.height,
            ContentRoi.full(frame.width, frame.height),
        )
        assertThat(full.gridConfidence).isWithin(5e-4f).of(0.9872f)
        assertThat(full.diagnostics["gridRecover"] ?: "none").isEqualTo("none")
        assertThat(full.validation.isPass).isTrue()

        // Live symptom: square contentRoi left≈7 (≈−13 vs golden 20) → first-pass ≈0.972.
        // Aspect ≤1.25 so playfield snap is a no-op; soft recovery must clear MIN_GRID.
        val misRoi = ContentRoi(7, 1206, 1047, 2246)
        val finder = BoardFinder()
        val recovered = finder.find(frame.pixels, frame.width, frame.height, misRoi)
        assertThat(recovered.grid.method).isEqualTo(GridMethod.PROJECTION)
        assertThat(recovered.grid.confidence).isAtLeast(VisionThresholds.MIN_GRID_CONFIDENCE)
        assertThat(recovered.diagnostics["gridRecover"]).isIn(setOf("soft_outlier", "soft_outlier_roi_nudge"))
        println(
            "LIVE_MISSNAP misRoi=$misRoi recover=${recovered.diagnostics["gridRecover"]} " +
                "gridConf=${"%.4f".format(recovered.grid.confidence)} " +
                "projRelVar=${recovered.diagnostics["projRelVarX"]}/${recovered.diagnostics["projRelVarY"]} " +
                "gate=${if (recovered.grid.confidence >= 0.98f) "PASS" else "HOLD"}",
        )
    }

    @Test
    fun realFrame_statusBarAndBubbleCoverTopUi_stillPass() {
        Assume.assumeTrue(RealFrameLoader.resourceExists())
        val frame = RealFrameLoader.loadFromResource()!!
        val pixels = frame.pixels.copyOf()
        val w = frame.width
        val h = frame.height
        // Paint a top-end bubble (118dp≈354px @3x) — MediaProjection composes overlay.
        val bw = 354
        val bh = 220
        val x0 = w - bw - 16
        val y0 = 24
        for (yy in y0 until (y0 + bh).coerceAtMost(h)) {
            for (xx in x0 until (x0 + bw).coerceAtMost(w)) {
                // ~80% dark overlay blend
                val p = pixels[yy * w + xx]
                val r = (PixelMath.red(p) * 0.2f + 27 * 0.8f).toInt().coerceIn(0, 255)
                val g = (PixelMath.green(p) * 0.2f + 27 * 0.8f).toInt().coerceIn(0, 255)
                val b = (PixelMath.blue(p) * 0.2f + 27 * 0.8f).toInt().coerceIn(0, 255)
                pixels[yy * w + xx] = PixelMath.rgb(r, g, b)
            }
        }
        val roi = ContentRoi(0, 88, w, h)
        val result = pipeline.analyze(pixels, w, h, roi)
        VisionDiagnostics.assertOrDump(
            result,
            result.validation.isPass,
            "top-end bubble + status-bar contentRoi must still PASS (gates unchanged)",
        )
        assertThat(result.gridConfidence).isAtLeast(VisionThresholds.MIN_GRID_CONFIDENCE)
        assertThat(result.boardConfidence).isAtLeast(VisionThresholds.MIN_BOARD_CONFIDENCE)
        assertThat(result.unknownCount).isAtMost(VisionThresholds.MAX_UNKNOWN_COUNT)
        println(
            "LIVE_BUBBLE contentRoi=$roi gate=PASS " +
                "gridConf=${"%.4f".format(result.gridConfidence)} " +
                "boardConf=${"%.4f".format(result.boardConfidence)} " +
                "recover=${result.diagnostics["gridRecover"]} " +
                "unk=${result.unknownCount}",
        )
    }

    @Test
    fun realFrame_clearBoard_unknownAtMostOne_includingMushroom() {
        Assume.assumeTrue(RealFrameLoader.resourceExists())
        val frame = RealFrameLoader.loadFromResource()!!
        val result = pipeline.analyze(
            frame.pixels, frame.width, frame.height,
            ContentRoi.full(frame.width, frame.height),
        )
        assertThat(VisionThresholds.MAX_UNKNOWN_COUNT).isEqualTo(1)
        VisionDiagnostics.assertOrDump(
            result,
            result.validation.isPass,
            "clear PvP (mushroom +3 present) must PASS with unk≤1",
        )
        assertThat(result.unknownCount).isAtMost(VisionThresholds.MAX_UNKNOWN_COUNT)
        assertThat(result.gridConfidence).isAtLeast(VisionThresholds.MIN_GRID_CONFIDENCE)
        assertThat(result.boardConfidence).isAtLeast(VisionThresholds.MIN_BOARD_CONFIDENCE)
        // Purple square that used to be high-conf STAR contradiction must be known.
        val r5c6 = result.board.get(5, 6)
        assertThat(r5c6.isUnknown).isFalse()
        assertThat(r5c6.color).isEqualTo(TileColor.P)
        assertThat(r5c6.shape).isEqualTo(TileShape.SQUARE)
        // Mushroom cell must not alone consume the unknown budget as UNKNOWN.
        val mush = result.board.get(4, 1)
        assertThat(mush.isUnknown).isFalse()
        println(
            "LIVE_CELLS unk=${result.unknownCount} gate=PASS " +
                "r5c6=${r5c6.color}/${r5c6.shape} mush=${mush.color}/${mush.shape} " +
                "boardConf=${"%.4f".format(result.boardConfidence)}",
        )
    }

    @Test
    fun realFrame_misSnapRoi_stillUnknownAtMostOne() {
        Assume.assumeTrue(RealFrameLoader.resourceExists())
        val frame = RealFrameLoader.loadFromResource()!!
        val misRoi = ContentRoi(7, 1206, 1047, 2246)
        val result = pipeline.analyze(frame.pixels, frame.width, frame.height, misRoi)
        assertThat(result.gridConfidence).isAtLeast(VisionThresholds.MIN_GRID_CONFIDENCE)
        assertThat(result.unknownCount).isAtMost(VisionThresholds.MAX_UNKNOWN_COUNT)
        VisionDiagnostics.assertOrDump(
            result,
            result.validation.isPass,
            "soft-recovered live mis-snap must PASS with unk≤1 (gates unchanged)",
        )
        println(
            "LIVE_CELLS_MISSNAP unk=${result.unknownCount} gate=PASS " +
                "grid=${"%.4f".format(result.gridConfidence)} " +
                "board=${"%.4f".format(result.boardConfidence)} " +
                "recover=${result.diagnostics["gridRecover"]}",
        )
    }

    @Test
    fun integrity_gatesUnchanged() {
        assertThat(VisionThresholds.MIN_GRID_CONFIDENCE).isEqualTo(0.98f)
        assertThat(VisionThresholds.MIN_BOARD_CONFIDENCE).isEqualTo(0.95f)
        assertThat(VisionThresholds.MAX_UNKNOWN_COUNT).isEqualTo(1)
    }
}
