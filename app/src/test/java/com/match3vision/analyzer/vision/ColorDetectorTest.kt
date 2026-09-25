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
    fun mixedTwoHues_documentsUncertainty() {
        val w = 24
        val h = 24
        val cell = IntArray(w * h) { i ->
            val x = i % w
            if (x < w / 2) SyntheticFrames.COLOR_B else SyntheticFrames.COLOR_R
        }
        val r = detector.detect(cell)
        assertThat(r.color).isAnyOf(TileColor.B, TileColor.R, TileColor.UNKNOWN)
        assertThat(r.confidence).isAtMost(0.70f)
    }

    @Test
    fun hueBucketBoundaries_documentCurrentMapping() {
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

    @Test
    fun shadedBlue_stillDetectsB() {
        val w = 24
        val h = 24
        val base = SyntheticFrames.COLOR_B
        val cell = IntArray(w * h) { i ->
            val y = i / w
            val t = y.toFloat() / (h - 1)
            blend(base, PixelMath.rgb(0, 0, 0), t * 0.30f)
        }
        val r = detector.detect(cell)
        assertThat(r.color).isEqualTo(TileColor.B)
        assertThat(r.confidence).isAtLeast(0.6f)
    }

    @Test
    fun antiAliasedEdge_redOnDark_stillR() {
        val w = 24
        val h = 24
        val cell = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            when {
                x in 4..19 && y in 4..19 -> SyntheticFrames.COLOR_R
                x in 3..20 && y in 3..20 -> blend(SyntheticFrames.COLOR_R, SyntheticFrames.DARK, 0.45f)
                else -> SyntheticFrames.DARK
            }
        }
        val r = detector.detect(cell)
        assertThat(r.color).isEqualTo(TileColor.R)
    }

    @Test
    fun brightnessBoostedGreen_stillG() {
        val base = SyntheticFrames.COLOR_G
        val cell = IntArray(24 * 24) {
            fun adj(v: Int) = (v * 1.15f).toInt().coerceIn(0, 255)
            PixelMath.rgb(adj(PixelMath.red(base)), adj(PixelMath.green(base)), adj(PixelMath.blue(base)))
        }
        val r = detector.detect(cell)
        assertThat(r.color).isEqualTo(TileColor.G)
    }

    @Test
    fun jpegQuantizedOrange_stillO() {
        val base = SyntheticFrames.COLOR_O
        val step = 16
        fun q(v: Int) = (v / step) * step
        val quant = PixelMath.rgb(q(PixelMath.red(base)), q(PixelMath.green(base)), q(PixelMath.blue(base)))
        val r = detector.detect(SyntheticFrames.solidCell(quant))
        assertThat(r.color).isEqualTo(TileColor.O)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        fun mix(x: Int, y: Int) = (x * (1 - tt) + y * tt).toInt().coerceIn(0, 255)
        return PixelMath.rgb(
            mix(PixelMath.red(a), PixelMath.red(b)),
            mix(PixelMath.green(a), PixelMath.green(b)),
            mix(PixelMath.blue(a), PixelMath.blue(b)),
        )
    }
}
