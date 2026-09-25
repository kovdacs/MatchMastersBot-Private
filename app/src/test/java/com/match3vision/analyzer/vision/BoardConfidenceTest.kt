package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import org.junit.Test

/**
 * Audits [VisionPipeline] boardConfidence behavior on synthetic clean vs degraded.
 * Does not loosen PASS/HOLD gates.
 */
class BoardConfidenceTest {

    private val pipeline = VisionPipeline()

    @Test
    fun idealizedCleanBoard_boardConfidencePassEligible() {
        val (pixels, w, h) = SyntheticFrames.letterboxedBoard(withGutters = true)
        val roi = SyntheticFrames.contentRoiForLetterbox()
        val result = pipeline.analyze(pixels, w, h, roi)
        assertThat(result.method).isEqualTo(GridMethod.PROJECTION)
        assertThat(result.unknownCount).isAtMost(VisionThresholds.MAX_UNKNOWN_COUNT)
        assertThat(result.boardConfidence).isAtLeast(VisionThresholds.MIN_BOARD_CONFIDENCE)
        assertThat(result.gridConfidence).isAtLeast(VisionThresholds.MIN_GRID_CONFIDENCE)
        assertThat(result.validation).isEqualTo(ValidationResult.Pass)
        println(
            "clean boardConf=${result.boardConfidence} gridConf=${result.gridConfidence} " +
                "unknowns=${result.unknownCount}",
        )
    }

    @Test
    fun evenSplitManyUnknowns_gateHold_despiteAnyBoardConf() {
        val (pixels, w, h) = SyntheticFrames.noGutterBoard()
        val result = pipeline.analyze(pixels, w, h, ContentRoi.full(w, h))
        assertThat(result.method).isEqualTo(GridMethod.EVEN_SPLIT)
        // EVEN_SPLIT grid conf ~0.72 < 0.98 → HOLD regardless of boardConfidence inflation
        assertThat(result.gridConfidence).isLessThan(VisionThresholds.MIN_GRID_CONFIDENCE)
        assertThat(result.validation.isPass).isFalse()
        assertThat(result.validation).isInstanceOf(ValidationResult.Hold::class.java)
        println(
            "evenSplit boardConf=${result.boardConfidence} gridConf=${result.gridConfidence} " +
                "unknowns=${result.unknownCount} gate=${result.validation}",
        )
    }

    @Test
    fun degradedNoise_mayHold_documentsScores() {
        val (base, w, h) = SyntheticFrames.letterboxedBoard(withGutters = true)
        val noisy = base.copyOf()
        var s = 7
        for (i in noisy.indices) {
            s = (s * 1103515245 + 12345) and 0x7fffffff
            val d = (s % 41) - 20
            fun ch(v: Int) = (v + d).coerceIn(0, 255)
            val p = noisy[i]
            noisy[i] = PixelMath.rgb(ch(PixelMath.red(p)), ch(PixelMath.green(p)), ch(PixelMath.blue(p)))
        }
        val roi = SyntheticFrames.contentRoiForLetterbox()
        val result = pipeline.analyze(noisy, w, h, roi)
        println(
            "degraded boardConf=${result.boardConfidence} gridConf=${result.gridConfidence} " +
                "unknowns=${result.unknownCount} gate=${result.validation}",
        )
        // Structural only — HOLD is acceptable
        assertThat(result.board.cells).hasLength(7)
    }
}
