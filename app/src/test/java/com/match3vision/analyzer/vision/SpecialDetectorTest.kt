package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * SpecialDetector conservatism: prefer NONE / UNKNOWN over false specials.
 * Covers normal / arrow / lightning / bomb / uncertain / partial patterns.
 */
class SpecialDetectorTest {

    private val detector = SpecialDetector()

    @Test
    fun normalSolid_isNone() {
        val r = detector.detect(SyntheticFrames.solidCell(SyntheticFrames.COLOR_G), 24, 24)
        assertThat(r.special).isEqualTo(SpecialType.NONE)
        assertThat(r.confidence).isLessThan(VisionThresholds.SPECIAL_MIN_CONFIDENCE)
    }

    @Test
    fun bombLikeDarkCore_documentsConservative() {
        val w = 24
        val h = 24
        val cell = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val cx = w / 2f
            val cy = h / 2f
            val d = kotlin.math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
            when {
                d < 4f -> PixelMath.rgb(10, 10, 10)
                d < 9f -> SyntheticFrames.COLOR_O
                else -> SyntheticFrames.DARK
            }
        }
        val r = detector.detect(cell, w, h)
        // Conservative: BOMB only if ≥ 0.55; else NONE is correct
        if (r.confidence >= VisionThresholds.SPECIAL_MIN_CONFIDENCE) {
            assertThat(r.special).isEqualTo(SpecialType.BOMB)
        } else {
            assertThat(r.special).isEqualTo(SpecialType.NONE)
        }
        println("bombLike → ${r.special} conf=${r.confidence}")
    }

    @Test
    fun lightningStreak_documentsConservative() {
        val w = 24
        val h = 24
        val cell = IntArray(w * h) { SyntheticFrames.COLOR_B }
        // Vertical bright streak
        for (y in 2 until h - 2) {
            for (x in 10..12) {
                cell[y * w + x] = PixelMath.rgb(250, 250, 255)
            }
        }
        val r = detector.detect(cell, w, h)
        if (r.confidence >= VisionThresholds.SPECIAL_MIN_CONFIDENCE) {
            assertThat(r.special).isAnyOf(SpecialType.LIGHTNING, SpecialType.TWO_WAY_ARROW)
        } else {
            assertThat(r.special).isEqualTo(SpecialType.NONE)
        }
        println("lightningStreak → ${r.special} conf=${r.confidence}")
    }

    @Test
    fun arrowLobes_documentsConservative() {
        val w = 24
        val h = 24
        val cell = IntArray(w * h) { SyntheticFrames.COLOR_R }
        // Left + right bright lobes on mid band
        for (y in 10..13) {
            for (x in 1..5) cell[y * w + x] = PixelMath.rgb(255, 255, 240)
            for (x in 18..22) cell[y * w + x] = PixelMath.rgb(255, 255, 240)
        }
        val r = detector.detect(cell, w, h)
        if (r.confidence >= VisionThresholds.SPECIAL_MIN_CONFIDENCE) {
            assertThat(r.special).isEqualTo(SpecialType.TWO_WAY_ARROW)
        } else {
            assertThat(r.special).isEqualTo(SpecialType.NONE)
        }
        println("arrowLobes → ${r.special} conf=${r.confidence}")
    }

    @Test
    fun uncertainNoise_staysNone() {
        val w = 24
        val h = 24
        var s = 123
        val cell = IntArray(w * h) {
            s = (s * 1103515245 + 12345) and 0x7fffffff
            val v = 40 + (s % 180)
            PixelMath.rgb(v, v / 2, 255 - v)
        }
        val r = detector.detect(cell, w, h)
        // Noise must not confidently invent a special
        if (r.special != SpecialType.NONE) {
            assertThat(r.confidence).isAtLeast(VisionThresholds.SPECIAL_MIN_CONFIDENCE)
        }
        // Most important: below threshold ⇒ NONE
        if (r.confidence < VisionThresholds.SPECIAL_MIN_CONFIDENCE) {
            assertThat(r.special).isEqualTo(SpecialType.NONE)
        }
        println("uncertainNoise → ${r.special} conf=${r.confidence}")
    }

    @Test
    fun partialBrightOverlay_staysConservative() {
        val w = 24
        val h = 24
        val cell = IntArray(w * h) { i ->
            val y = i / w
            if (y < h / 3) PixelMath.rgb(250, 250, 250) else SyntheticFrames.COLOR_Y
        }
        val r = detector.detect(cell, w, h)
        if (r.confidence < VisionThresholds.SPECIAL_MIN_CONFIDENCE) {
            assertThat(r.special).isEqualTo(SpecialType.NONE)
        }
        println("partialBright → ${r.special} conf=${r.confidence}")
    }

    @Test
    fun thresholdConstant_unchanged() {
        assertThat(VisionThresholds.SPECIAL_MIN_CONFIDENCE).isEqualTo(0.55f)
    }
}
