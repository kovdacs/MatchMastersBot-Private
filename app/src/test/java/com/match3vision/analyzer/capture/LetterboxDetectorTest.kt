package com.match3vision.analyzer.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM unit tests using synthetic ARGB pixel buffers (no Robolectric / Bitmap required).
 */
class LetterboxDetectorTest {

    private val black = rgb(0, 0, 0)
    private val nearBlack = rgb(8, 8, 8)
    private val content = rgb(200, 80, 40)

    @Test
    fun noBars_returnsFullFrame() {
        val w = 40
        val h = 60
        val pixels = IntArray(w * h) { content }
        val roi = LetterboxDetector.detect(pixels, w, h, lumaThreshold = 16, minBarRatio = 0.02f)
        assertThat(roi).isEqualTo(ContentRoi(0, 0, w, h))
    }

    @Test
    fun topAndBottomLetterbox_detected() {
        val w = 40
        val h = 80
        val bar = 10
        val pixels = IntArray(w * h) { content }
        // top bar
        for (y in 0 until bar) {
            for (x in 0 until w) pixels[y * w + x] = black
        }
        // bottom bar
        for (y in (h - bar) until h) {
            for (x in 0 until w) pixels[y * w + x] = black
        }
        val roi = LetterboxDetector.detect(pixels, w, h, lumaThreshold = 16, minBarRatio = 0.02f)
        assertThat(roi.left).isEqualTo(0)
        assertThat(roi.right).isEqualTo(w)
        assertThat(roi.top).isEqualTo(bar)
        assertThat(roi.bottom).isEqualTo(h - bar)
        assertThat(roi.height()).isEqualTo(h - 2 * bar)
    }

    @Test
    fun leftAndRightPillarbox_detected() {
        val w = 80
        val h = 40
        val bar = 12
        val pixels = IntArray(w * h) { content }
        for (y in 0 until h) {
            for (x in 0 until bar) pixels[y * w + x] = black
            for (x in (w - bar) until w) pixels[y * w + x] = black
        }
        val roi = LetterboxDetector.detect(pixels, w, h, lumaThreshold = 16, minBarRatio = 0.02f)
        assertThat(roi.top).isEqualTo(0)
        assertThat(roi.bottom).isEqualTo(h)
        assertThat(roi.left).isEqualTo(bar)
        assertThat(roi.right).isEqualTo(w - bar)
        assertThat(roi.width()).isEqualTo(w - 2 * bar)
    }

    @Test
    fun allFourBars_detected() {
        val w = 100
        val h = 80
        val top = 8
        val bottom = 6
        val left = 10
        val right = 12
        val pixels = IntArray(w * h) { black }
        for (y in top until (h - bottom)) {
            for (x in left until (w - right)) {
                pixels[y * w + x] = content
            }
        }
        val roi = LetterboxDetector.detect(pixels, w, h, lumaThreshold = 16, minBarRatio = 0.02f)
        assertThat(roi.left).isEqualTo(left)
        assertThat(roi.top).isEqualTo(top)
        assertThat(roi.right).isEqualTo(w - right)
        assertThat(roi.bottom).isEqualTo(h - bottom)
    }

    @Test
    fun nearBlackBars_stillDetected() {
        val w = 50
        val h = 50
        val bar = 5
        val pixels = IntArray(w * h) { content }
        for (y in 0 until bar) {
            for (x in 0 until w) pixels[y * w + x] = nearBlack
        }
        for (y in (h - bar) until h) {
            for (x in 0 until w) pixels[y * w + x] = nearBlack
        }
        val roi = LetterboxDetector.detect(pixels, w, h, lumaThreshold = 16, minBarRatio = 0.02f)
        assertThat(roi.top).isEqualTo(bar)
        assertThat(roi.bottom).isEqualTo(h - bar)
    }

    @Test
    fun thinDarkStrip_belowMinRatio_notTrimmed() {
        val w = 100
        val h = 100
        // Single dark row at top — below 2% of height (need ≥2 px for 0.02f)
        val pixels = IntArray(w * h) { content }
        for (x in 0 until w) pixels[x] = black // y=0 only
        val roi = LetterboxDetector.detect(pixels, w, h, lumaThreshold = 16, minBarRatio = 0.05f)
        // 1px bar < 5% of 100 → should NOT trim
        assertThat(roi.top).isEqualTo(0)
        assertThat(roi).isEqualTo(ContentRoi(0, 0, w, h))
    }

    @Test
    fun luma_blackIsZero_whiteIs255() {
        assertThat(LetterboxDetector.luma(rgb(0, 0, 0))).isEqualTo(0)
        assertThat(LetterboxDetector.luma(rgb(255, 255, 255))).isEqualTo(255)
    }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
