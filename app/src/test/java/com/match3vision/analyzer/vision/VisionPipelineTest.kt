package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.match3vision.analyzer.capture.ContentRoi
import org.junit.Test

class VisionPipelineTest {

    private val pipeline = VisionPipeline()

    @Test
    fun cleanBoard_pipelineProduces7x7AndPreferPassOrHoldMessage() {
        val (pixels, w, h) = SyntheticFrames.letterboxedBoard(withGutters = true)
        val roi = SyntheticFrames.contentRoiForLetterbox()
        val result = pipeline.analyze(pixels, w, h, roi)

        assertThat(result.grid.xBoundaries).hasLength(8)
        assertThat(result.grid.yBoundaries).hasLength(8)
        assertThat(result.board.cells).hasLength(7)
        assertThat(result.board.cells[0]).hasLength(7)
        assertThat(result.method).isEqualTo(GridMethod.PROJECTION)
        assertThat(result.unknownCount).isAtMost(49)
        // Diagnostics present
        assertThat(result.diagnostics).containsKey("method")
        // With solid palette cells + projection, expect few unknowns and PASS
        assertWithMessage(
            "unknownCount=%s validation=%s method=%s gridConf=%s diag=%s",
            result.unknownCount,
            result.validation,
            result.method,
            result.gridConfidence,
            result.diagnostics,
        ).that(result.unknownCount).isAtMost(1)
        assertThat(result.gridConfidence).isAtLeast(VisionThresholds.MIN_GRID_CONFIDENCE)
        assertThat(result.validation).isEqualTo(ValidationResult.Pass)
    }

    @Test
    fun occludedCell_inPipeline_isUnknown() {
        // Build tiny 7×7 even board then darken one cell region after geometry
        val size = 70
        val pixels = IntArray(size * size) { SyntheticFrames.COLOR_B }
        // Dark gutters every 10px
        for (i in 0..7) {
            val g = i * 10
            if (g < size) {
                for (y in 0 until size) pixels[y * size + g] = SyntheticFrames.GUTTER
                for (x in 0 until size) pixels[g * size + x] = SyntheticFrames.GUTTER
            }
        }
        // Occlude cell (0,0) interior
        for (y in 1 until 9) {
            for (x in 1 until 9) {
                pixels[y * size + x] = SyntheticFrames.DARK
            }
        }
        val result = pipeline.analyze(pixels, size, size, ContentRoi.full(size, size))
        val cell00 = result.board.get(0, 0)
        assertThat(cell00.isUnknown).isTrue()
        assertThat(cell00.occluded).isTrue()
    }

    @Test
    fun manyUnknowns_gateHold() {
        // Almost all dark → many occluded unknowns
        val size = 70
        val pixels = IntArray(size * size) { SyntheticFrames.DARK }
        for (i in 0..7) {
            val g = i * 10
            if (g < size) {
                for (y in 0 until size) pixels[y * size + g] = SyntheticFrames.GUTTER
                for (x in 0 until size) pixels[g * size + x] = SyntheticFrames.GUTTER
            }
        }
        val result = pipeline.analyze(pixels, size, size, ContentRoi.full(size, size))
        assertThat(result.unknownCount).isGreaterThan(1)
        assertThat(result.validation.isPass).isFalse()
        assertThat(result.validation).isInstanceOf(ValidationResult.Hold::class.java)
    }

    @Test
    fun fallbackEvenSplit_whenNoGutters() {
        val (pixels, w, h) = SyntheticFrames.noGutterBoard()
        val result = pipeline.analyze(pixels, w, h, ContentRoi.full(w, h))
        assertThat(result.method).isEqualTo(GridMethod.EVEN_SPLIT)
        // EVEN_SPLIT grid confidence is below PASS threshold → HOLD
        assertThat(result.validation.isPass).isFalse()
    }
}
