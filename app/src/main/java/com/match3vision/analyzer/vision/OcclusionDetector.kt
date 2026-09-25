package com.match3vision.analyzer.vision

/**
 * V3.1-style occlusion / banner / dark-cell detection.
 *
 * - Dark or banner-covered cells → mark occluded (caller sets UNKNOWN).
 * - **No interpolation** of occluded cells.
 * - Orange/red banner text must not be treated as valid tile content.
 *
 * Pipeline short-circuit: crop → occlusion → if occluded UNKNOWN (skip color/shape/special).
 */
class OcclusionDetector(
    private val darkLumaThreshold: Float = 28f,
    private val darkFractionThreshold: Float = 0.72f,
    private val bannerWarmFractionThreshold: Float = 0.50f,
) {

    data class Result(
        val occluded: Boolean,
        val reason: String,
        val confidence: Float,
    )

    fun detect(cellPixels: IntArray, cellWidth: Int, cellHeight: Int): Result {
        if (cellPixels.isEmpty() || cellWidth <= 0 || cellHeight <= 0) {
            return Result(true, "empty_crop", 1f)
        }

        var dark = 0
        var warmBanner = 0
        var samples = 0
        val hsv = FloatArray(3)
        val stepX = PixelMath.sampleStep(cellWidth, 24)
        val stepY = PixelMath.sampleStep(cellHeight, 24)

        var y = 0
        while (y < cellHeight) {
            var x = 0
            while (x < cellWidth) {
                val p = cellPixels[y * cellWidth + x]
                val L = PixelMath.luma(p)
                if (L <= darkLumaThreshold) dark++
                PixelMath.rgbToHsv(p, hsv)
                // Banner: high-sat warm hue, often mid/high value (text glow) —
                // count as occlusion cover, not as tile.
                if (hsv[1] > 0.50f && hsv[2] > 0.40f && isBannerHue(hsv[0])) {
                    warmBanner++
                }
                samples++
                x += stepX
            }
            y += stepY
        }

        if (samples == 0) return Result(true, "no_samples", 1f)
        val darkFrac = dark.toFloat() / samples
        val bannerFrac = warmBanner.toFloat() / samples

        if (darkFrac >= darkFractionThreshold) {
            return Result(true, "dark_cell", darkFrac.coerceIn(0f, 1f))
        }
        if (bannerFrac >= bannerWarmFractionThreshold) {
            return Result(true, "banner_overlay", bannerFrac.coerceIn(0f, 1f))
        }

        // Near-uniform very dark mean
        val meanL = PixelMath.meanLuma(cellPixels)
        if (meanL < darkLumaThreshold * 0.85f) {
            return Result(true, "mean_dark", 0.9f)
        }

        return Result(false, "clear", 1f - darkFrac)
    }

    private fun isBannerHue(h: Float): Boolean = h <= 45f || h >= 345f
}
