package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.sqrt

class ShapeDetectorTest {

    private val shape = ShapeDetector()

    @Test
    fun solidBlueFill_isUnknownNotCircle() {
        val cell = SyntheticFrames.solidCell(SyntheticFrames.COLOR_B, 24, 24)
        val r = shape.detect(cell, 24, 24)
        assertThat(r.shape).isEqualTo(TileShape.UNKNOWN)
        assertThat(r.confidence).isLessThan(VisionThresholds.RECONCILE_HIGH_CONFIDENCE)
    }

    @Test
    fun solidRedFill_isUnknownNotCircle() {
        val cell = SyntheticFrames.solidCell(SyntheticFrames.COLOR_R, 24, 24)
        val r = shape.detect(cell, 24, 24)
        assertThat(r.shape).isEqualTo(TileShape.UNKNOWN)
        assertThat(r.confidence).isLessThan(VisionThresholds.RECONCILE_HIGH_CONFIDENCE)
    }

    @Test
    fun carvedCircle_isDetectedAsCircle() {
        val w = 24
        val h = 24
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val radius = 8.5f
        val bg = SyntheticFrames.DARK
        val fg = SyntheticFrames.COLOR_R
        val cell = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
            if (d <= radius) fg else bg
        }
        val r = shape.detect(cell, w, h)
        assertThat(r.shape).isEqualTo(TileShape.CIRCLE)
        assertThat(r.confidence).isAtLeast(0.55f)
    }
}
