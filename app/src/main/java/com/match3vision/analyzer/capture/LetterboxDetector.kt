package com.match3vision.analyzer.capture

import android.graphics.Bitmap

/**
 * Detects near-black letterbox / pillarbox bars and returns the content [ContentRoi].
 *
 * Pure Kotlin (no OpenCV). The [IntArray] overload is fully JVM-testable without
 * Robolectric; the [Bitmap] overload is a thin wrapper for device / instrumented use.
 */
object LetterboxDetector {

    fun detect(
        bitmap: Bitmap,
        lumaThreshold: Int = CaptureConfig.DEFAULT_LETTERBOX_LUMA,
        minBarRatio: Float = CaptureConfig.DEFAULT_BAR_MIN_RATIO,
    ): ContentRoi {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return ContentRoi(0, 0, w.coerceAtLeast(0), h.coerceAtLeast(0))
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return detect(pixels, w, h, lumaThreshold, minBarRatio)
    }

    /**
     * Detect content ROI from packed ARGB pixels (row-major).
     * Suitable for synthetic fixtures in JVM unit tests.
     */
    fun detect(
        pixels: IntArray,
        width: Int,
        height: Int,
        lumaThreshold: Int = CaptureConfig.DEFAULT_LETTERBOX_LUMA,
        minBarRatio: Float = CaptureConfig.DEFAULT_BAR_MIN_RATIO,
    ): ContentRoi {
        require(pixels.size >= width * height) { "pixels too small for ${width}x$height" }
        if (width <= 0 || height <= 0) {
            return ContentRoi(0, 0, width.coerceAtLeast(0), height.coerceAtLeast(0))
        }

        val top = findTopBar(pixels, width, height, lumaThreshold)
        val bottom = findBottomBar(pixels, width, height, lumaThreshold)
        val left = findLeftBar(pixels, width, height, lumaThreshold)
        val right = findRightBar(pixels, width, height, lumaThreshold)

        val minH = (height * minBarRatio).toInt().coerceAtLeast(1)
        val minW = (width * minBarRatio).toInt().coerceAtLeast(1)

        val contentTop = if (top == 0 || top >= minH) top else 0
        val contentBottom = if (bottom == 0 || bottom >= minH) height - bottom else height
        val contentLeft = if (left == 0 || left >= minW) left else 0
        val contentRight = if (right == 0 || right >= minW) width - right else width

        val l = contentLeft.coerceIn(0, (width - 1).coerceAtLeast(0))
        val t = contentTop.coerceIn(0, (height - 1).coerceAtLeast(0))
        val r = contentRight.coerceIn(l + 1, width)
        val b = contentBottom.coerceIn(t + 1, height)
        return ContentRoi(l, t, r, b)
    }

    private fun findTopBar(pixels: IntArray, w: Int, h: Int, threshold: Int): Int {
        var y = 0
        while (y < h / 2) {
            if (!isDarkRow(pixels, w, y, threshold)) break
            y++
        }
        return y
    }

    private fun findBottomBar(pixels: IntArray, w: Int, h: Int, threshold: Int): Int {
        var y = h - 1
        var count = 0
        while (y >= h / 2) {
            if (!isDarkRow(pixels, w, y, threshold)) break
            count++
            y--
        }
        return count
    }

    private fun findLeftBar(pixels: IntArray, w: Int, h: Int, threshold: Int): Int {
        var x = 0
        while (x < w / 2) {
            if (!isDarkColumn(pixels, w, h, x, threshold)) break
            x++
        }
        return x
    }

    private fun findRightBar(pixels: IntArray, w: Int, h: Int, threshold: Int): Int {
        var x = w - 1
        var count = 0
        while (x >= w / 2) {
            if (!isDarkColumn(pixels, w, h, x, threshold)) break
            count++
            x--
        }
        return count
    }

    private fun isDarkRow(pixels: IntArray, w: Int, y: Int, threshold: Int): Boolean {
        var sum = 0L
        val row = y * w
        val step = (w / 64).coerceAtLeast(1)
        var samples = 0
        var x = 0
        while (x < w) {
            sum += luma(pixels[row + x])
            samples++
            x += step
        }
        return samples > 0 && (sum / samples) <= threshold
    }

    private fun isDarkColumn(pixels: IntArray, w: Int, h: Int, x: Int, threshold: Int): Boolean {
        var sum = 0L
        val step = (h / 64).coerceAtLeast(1)
        var samples = 0
        var y = 0
        while (y < h) {
            sum += luma(pixels[y * w + x])
            samples++
            y += step
        }
        return samples > 0 && (sum / samples) <= threshold
    }

    /** Rec. 601 luma from ARGB packed int (bit shifts — no Android Color dependency). */
    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
