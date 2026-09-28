package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import org.junit.Test

/**
 * EVEN_SPLIT fallback confidence is 0.72 — must NOT PASS validator
 * (MIN_GRID_CONFIDENCE = 0.98). Fail closed; improve ROI/projection instead.
 */
class EvenSplitFailClosedTest {

    @Test
    fun evenSplit072_cannotPassValidator() {
        val grid = GridGeometry.evenSplit(ContentRoi(0, 0, 700, 700), confidence = 0.72f)
        assertThat(grid.method).isEqualTo(GridMethod.EVEN_SPLIT)
        assertThat(grid.confidence).isEqualTo(0.72f)
        assertThat(grid.confidence).isLessThan(VisionThresholds.MIN_GRID_CONFIDENCE)
        val gate = VisionValidator().validate(
            boardConfidence = 1f,
            gridConfidence = grid.confidence,
            unknownCount = 0,
        )
        assertThat(gate).isInstanceOf(ValidationResult.Hold::class.java)
        assertThat((gate as ValidationResult.Hold).reason.lowercase()).contains("grid")
    }

    @Test
    fun boardFinderFallback_diagMarksEvenSplit() {
        // Degenerate flat frame → projection fails → EVEN_SPLIT @ 0.72 → HOLD
        val w = 64
        val h = 64
        val pixels = IntArray(w * h) { 0xFF808080.toInt() }
        val found = BoardFinder().find(pixels, w, h, ContentRoi.full(w, h))
        assertThat(found.diagnostics["fallback"] ?: found.diagnostics["method"])
            .isAnyOf("EVEN_SPLIT", GridMethod.EVEN_SPLIT.name)
        if (found.grid.method == GridMethod.EVEN_SPLIT) {
            assertThat(found.grid.confidence).isLessThan(VisionThresholds.MIN_GRID_CONFIDENCE)
        }
    }
}
