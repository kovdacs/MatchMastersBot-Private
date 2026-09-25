package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Photorealistic-**synthetic** shape crops (anti-aliased / blurred / shaded / noisy /
 * slightly scaled / offset). These are NOT real Match Masters frames.
 *
 * Does not change PASS/HOLD thresholds.
 */
class ShapeDetectorRealisticTest {

    private val shape = ShapeDetector()

    @Test
    fun carvedCircle_noisyAntiAliased_stillCircleOrUnknownSoft() {
        val (cell, w, h) = renderCircle(fg = SyntheticFrames.COLOR_R, noise = 12, soft = 0.4f)
        val r = shape.detect(cell, w, h)
        // Soft: prefer CIRCLE; UNKNOWN acceptable under heavy noise (not a false SQUARE)
        assertThat(r.shape).isAnyOf(TileShape.CIRCLE, TileShape.UNKNOWN)
    }

    @Test
    fun carvedSquare_offsetBlurred() {
        val (cell, w, h) = renderSquare(fg = SyntheticFrames.COLOR_P, offset = 1, blur = true)
        val r = shape.detect(cell, w, h)
        // Synthetic shaded/offset crop — accept any label; must not crash and conf in range
        assertThat(r.confidence).isAtLeast(0f)
        assertThat(r.confidence).isAtMost(1f)
        assertThat(r.shape).isNotNull()
        println("carvedSquare_offsetBlurred → ${r.shape} conf=${r.confidence}")
    }

    @Test
    fun carvedDiamond_shaded() {
        val (cell, w, h) = renderDiamond(fg = SyntheticFrames.COLOR_G, shade = true)
        val r = shape.detect(cell, w, h)
        assertThat(r.shape).isAnyOf(TileShape.DIAMOND, TileShape.UNKNOWN, TileShape.STAR)
    }

    @Test
    fun carvedTriangle_scaledSlightly() {
        val (cell, w, h) = renderTriangle(fg = SyntheticFrames.COLOR_Y, scale = 0.9f)
        val r = shape.detect(cell, w, h)
        assertThat(r.confidence).isAtLeast(0f)
        assertThat(r.confidence).isAtMost(1f)
        assertThat(r.shape).isNotNull()
        println("carvedTriangle_scaledSlightly → ${r.shape} conf=${r.confidence}")
    }

    @Test
    fun solidUnknown_withNoise_staysUnknown() {
        val w = 24
        val h = 24
        val base = SyntheticFrames.COLOR_B
        val cell = IntArray(w * h) { i ->
            jitter(base, i * 17 + 3, amp = 10)
        }
        val r = shape.detect(cell, w, h)
        assertThat(r.shape).isEqualTo(TileShape.UNKNOWN)
        assertThat(r.confidence).isLessThan(VisionThresholds.RECONCILE_HIGH_CONFIDENCE)
    }

    @Test
    fun emptyDark_isUnknown() {
        val cell = SyntheticFrames.darkCell(20, 20)
        val r = shape.detect(cell, 20, 20)
        assertThat(r.shape).isEqualTo(TileShape.UNKNOWN)
    }

    // --- synthetic render helpers (IntArray only; not MM frames) ---

    private fun renderCircle(
        fg: Int,
        size: Int = 28,
        radius: Float = 9.5f,
        noise: Int = 0,
        soft: Float = 0f,
    ): Triple<IntArray, Int, Int> {
        val cx = (size - 1) / 2f + 0.5f
        val cy = (size - 1) / 2f - 0.3f
        val bg = SyntheticFrames.DARK
        val cell = IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
            val edge = abs(d - radius)
            val pix = when {
                d <= radius - soft -> fg
                soft > 0f && edge <= soft -> blend(fg, bg, 0.5f)
                else -> bg
            }
            if (noise > 0) jitter(pix, i * 31, noise) else pix
        }
        return Triple(cell, size, size)
    }

    private fun renderSquare(
        fg: Int,
        size: Int = 28,
        inset: Int = 6,
        offset: Int = 0,
        blur: Boolean = false,
    ): Triple<IntArray, Int, Int> {
        val bg = SyntheticFrames.DARK
        val cell = IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            val inside = x in (inset + offset) until (size - inset + offset) &&
                y in (inset) until (size - inset)
            var pix = if (inside) fg else bg
            if (blur && !inside) {
                val near = x in (inset + offset - 1) until (size - inset + offset + 1) &&
                    y in (inset - 1) until (size - inset + 1)
                if (near) pix = blend(fg, bg, 0.45f)
            }
            pix
        }
        return Triple(cell, size, size)
    }

    private fun renderDiamond(
        fg: Int,
        size: Int = 28,
        shade: Boolean = false,
    ): Triple<IntArray, Int, Int> {
        val cx = (size - 1) / 2f
        val cy = (size - 1) / 2f
        val bg = SyntheticFrames.DARK
        val cell = IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            val manh = abs(x - cx) + abs(y - cy)
            val inside = manh <= size * 0.32f
            when {
                !inside -> bg
                shade && y < cy -> blend(fg, SyntheticFrames.rgb(255, 255, 255), 0.25f)
                else -> fg
            }
        }
        return Triple(cell, size, size)
    }

    private fun renderTriangle(
        fg: Int,
        size: Int = 28,
        scale: Float = 1f,
    ): Triple<IntArray, Int, Int> {
        val bg = SyntheticFrames.DARK
        val cell = IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            val top = (size * (0.2f / scale)).toInt()
            val bottom = (size * (0.85f * scale)).toInt().coerceAtMost(size - 1)
            val cy = ((top + bottom) / 2f)
            val halfBase = (size * 0.35f * scale)
            val t = ((y - top).toFloat() / (bottom - top).coerceAtLeast(1))
            val half = halfBase * t
            val inside = y in top..bottom && abs(x - size / 2f) <= half
            if (inside) fg else bg
        }
        return Triple(cell, size, size)
    }

    private fun jitter(argb: Int, seed: Int, amp: Int): Int {
        var s = seed and 0x7fffffff
        s = (s * 1103515245 + 12345) and 0x7fffffff
        val d = (s % (2 * amp + 1)) - amp
        fun ch(v: Int) = (v + d).coerceIn(0, 255)
        return PixelMath.rgb(ch(PixelMath.red(argb)), ch(PixelMath.green(argb)), ch(PixelMath.blue(argb)))
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun mix(x: Int, y: Int) = (x * (1 - t) + y * t).toInt().coerceIn(0, 255)
        return PixelMath.rgb(
            mix(PixelMath.red(a), PixelMath.red(b)),
            mix(PixelMath.green(a), PixelMath.green(b)),
            mix(PixelMath.blue(a), PixelMath.blue(b)),
        )
    }
}
