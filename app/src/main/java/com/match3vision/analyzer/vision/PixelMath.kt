package com.match3vision.analyzer.vision

/**
 * Pure-Kotlin ARGB pixel helpers (no Android [android.graphics.Color], no OpenCV).
 * All functions are JVM-unit-test friendly.
 */
object PixelMath {

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun rgb(r: Int, g: Int, b: Int): Int = argb(255, r, g, b)

    fun red(c: Int): Int = (c shr 16) and 0xFF
    fun green(c: Int): Int = (c shr 8) and 0xFF
    fun blue(c: Int): Int = c and 0xFF

    /** Rec. 601 luma 0..255. */
    fun luma(argb: Int): Int {
        val r = red(argb)
        val g = green(argb)
        val b = blue(argb)
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /**
     * RGB → HSV. H in [0,360), S/V in [0,1].
     */
    fun rgbToHsv(r: Int, g: Int, b: Int, out: FloatArray = FloatArray(3)): FloatArray {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val delta = max - min
        val h = when {
            delta < 1e-6f -> 0f
            max == rf -> 60f * (((gf - bf) / delta) % 6f)
            max == gf -> 60f * (((bf - rf) / delta) + 2f)
            else -> 60f * (((rf - gf) / delta) + 4f)
        }
        out[0] = if (h < 0f) h + 360f else h
        out[1] = if (max <= 1e-6f) 0f else delta / max
        out[2] = max
        return out
    }

    fun rgbToHsv(argb: Int, out: FloatArray = FloatArray(3)): FloatArray =
        rgbToHsv(red(argb), green(argb), blue(argb), out)

    /**
     * Extract an inclusive-exclusive crop [left,right) × [top,bottom) into a new buffer.
     */
    fun crop(
        pixels: IntArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Pair<IntArray, Pair<Int, Int>> {
        val l = left.coerceIn(0, width)
        val t = top.coerceIn(0, height)
        val r = right.coerceIn(l, width)
        val b = bottom.coerceIn(t, height)
        val cw = (r - l).coerceAtLeast(0)
        val ch = (b - t).coerceAtLeast(0)
        val out = IntArray(cw * ch)
        for (y in 0 until ch) {
            val src = (t + y) * width + l
            System.arraycopy(pixels, src, out, y * cw, cw)
        }
        return out to (cw to ch)
    }

    fun meanLuma(pixels: IntArray): Float {
        if (pixels.isEmpty()) return 0f
        var sum = 0L
        for (p in pixels) sum += luma(p)
        return sum.toFloat() / pixels.size
    }

    fun sampleStep(size: Int, maxSamples: Int = 64): Int =
        (size / maxSamples).coerceAtLeast(1)
}
