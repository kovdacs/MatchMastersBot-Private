package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Documents current [ColorDetector] hue buckets (no threshold loosening).
 *
 * Buckets (H in degrees): R [345,15)∪, O [15,40), Y [40,70), G [70,160),
 * B [160,255), P [255,310), else R.
 */
class ColorDetectorTest {

    private val detector = ColorDetector()

    @Test
    fun solidBlue_detectsB() {
        val r = detector.detect(SyntheticFrames.solidCell(SyntheticFrames.COLOR_B))
        assertThat(r.color).isEqualTo(TileColor.B)
        assertThat(r.confidence).isAtLeast(0.8f)
    }

    @Test
    fun solidRed_detectsR() {
        val r = detector.detect(SyntheticFrames.solidCell(SyntheticFrames.COLOR_R))
        assertThat(r.color).isEqualTo(TileColor.R)
        assertThat(r.confidence).isAtLeast(0.8f)
    }

    @Test
    fun solidYellow_detectsY() {
        val r = detector.detect(SyntheticFrames.solidCell(SyntheticFrames.COLOR_Y))
        assertThat(r.color).isEqualTo(TileColor.Y)
        assertThat(r.confidence).isAtLeast(0.8f)
    }

    @Test
    fun solidGreen_detectsG() {
        val r = detector.detect(SyntheticFrames.solidCell(SyntheticFrames.COLOR_G))
        assertThat(r.color).isEqualTo(TileColor.G)
        assertThat(r.confidence).isAtLeast(0.8f)
    }

    @Test
    fun solidPurple_detectsP() {
        val r = detector.detect(SyntheticFrames.solidCell(SyntheticFrames.COLOR_P))
        assertThat(r.color).isEqualTo(TileColor.P)
        assertThat(r.confidence).isAtLeast(0.8f)
    }

    @Test
    fun solidOrange_detectsO() {
        val r = detector.detect(SyntheticFrames.solidCell(SyntheticFrames.COLOR_O))
        assertThat(r.color).isEqualTo(TileColor.O)
        assertThat(r.confidence).isAtLeast(0.8f)
    }

    @Test
    fun lowSaturationGray_isUnknown() {
        val gray = SyntheticFrames.rgb(128, 128, 128)
        val r = detector.detect(SyntheticFrames.solidCell(gray))
        assertThat(r.color).isEqualTo(TileColor.UNKNOWN)
        assertThat(r.confidence).isLessThan(0.5f)
    }

    @Test
    fun mixedTwoHues_lowerConfidenceOrUnknownDominant() {
        val w = 24
        val h = 24
        val cell = IntArray(w * h) { i ->
            if (i % 2 == 0) SyntheticFrames.COLOR_B else SyntheticFrames.COLOR_R
        }
        val r = detector.detect(cell)
        // Dominant may still win but confidence should reflect split (~0.5)
        assertThat(r.confidence).isAtMost(0.65f)
        assertThat(r.color).isAnyOf(TileColor.B, TileColor.R, TileColor.UNKNOWN)
    }

    @Test
    fun hueBucketBoundaries_documentCurrentMapping() {
        // Spot-check representative hues via solid RGB near bucket centers
        val samples = listOf(
            SyntheticFrames.rgb(220, 30, 30) to TileColor.R,
            SyntheticFrames.rgb(240, 140, 30) to TileColor.O,
            SyntheticFrames.rgb(230, 210, 40) to TileColor.Y,
            SyntheticFrames.rgb(40, 180, 70) to TileColor.G,
            SyntheticFrames.rgb(40, 90, 220) to TileColor.B,
            SyntheticFrames.rgb(150, 50, 200) to TileColor.P,
        )
        for ((rgb, expected) in samples) {
            val r = detector.detect(SyntheticFrames.solidCell(rgb))
            assertThat(r.color).isEqualTo(expected)
        }
    }
}
