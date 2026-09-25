package com.match3vision.analyzer.vision

/**
 * V3.1-style occlusion / banner / dark-cell detection.
 *
 * - Dark or banner-covered cells → mark occluded (caller sets UNKNOWN).
 * - **No interpolation** of occluded cells.
 * - Orange/red **banner text / textured overlay** must not be treated as valid tile content.
 * - Solid (near-uniform) warm fills are valid R/O game tiles, not banners.
 * - Partial UI / dark overlays covering ≥ ~50% of the crop are occluded
 *   (catches 50–70% partial occlusion without false-positive solid R/O).
 *
 * Pipeline short-circuit: crop → occlusion → if occluded UNKNOWN (skip color/shape/special).
 */
class OcclusionDetector(
    private val darkLumaThreshold: Float = 28f,
    private val darkFractionThreshold: Float = 0.72f,
    /** Partial dark / dim overlay (50–70% coverage class). */
    private val partialDarkFractionThreshold: Float = 0.50f,
    private val bannerWarmFractionThreshold: Float = 0.50f,
    /**
     * Minimum RGB variance among warm (banner-hue) samples required to call
     * [banner_overlay]. Checker / multi-hue banner overlays are high; solid R/O tiles ~0.
     */
    private val bannerWarmColorVarianceMin: Float = 200f,
    private val uiOverlayFractionThreshold: Float = 0.50f,
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
        var uiOverlay = 0
        var samples = 0
        var warmSumR = 0.0
        var warmSumG = 0.0
        var warmSumB = 0.0
        var warmSumR2 = 0.0
        var warmSumG2 = 0.0
        var warmSumB2 = 0.0
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
                // Banner-hue vote: high-sat warm hue. Solid R/O tiles also match hue;
                // texture (RGB variance among warm samples) separates overlay from tile.
                if (hsv[1] > 0.50f && hsv[2] > 0.40f && isBannerHue(hsv[0])) {
                    warmBanner++
                    val r = PixelMath.red(p).toDouble()
                    val g = PixelMath.green(p).toDouble()
                    val b = PixelMath.blue(p).toDouble()
                    warmSumR += r
                    warmSumG += g
                    warmSumB += b
                    warmSumR2 += r * r
                    warmSumG2 += g * g
                    warmSumB2 += b * b
                }
                if (isUiOverlaySample(hsv)) uiOverlay++
                samples++
                x += stepX
            }
            y += stepY
        }

        if (samples == 0) return Result(true, "no_samples", 1f)
        val darkFrac = dark.toFloat() / samples
        val bannerFrac = warmBanner.toFloat() / samples
        val uiFrac = uiOverlay.toFloat() / samples

        if (darkFrac >= darkFractionThreshold) {
            return Result(true, "dark_cell", darkFrac.coerceIn(0f, 1f))
        }
        if (bannerFrac >= bannerWarmFractionThreshold && warmBanner > 0) {
            val n = warmBanner.toDouble()
            val varR = (warmSumR2 / n) - (warmSumR / n) * (warmSumR / n)
            val varG = (warmSumG2 / n) - (warmSumG / n) * (warmSumG / n)
            val varB = (warmSumB2 / n) - (warmSumB / n) * (warmSumB / n)
            val warmVar = (varR + varG + varB).toFloat()
            if (warmVar >= bannerWarmColorVarianceMin) {
                return Result(true, "banner_overlay", bannerFrac.coerceIn(0f, 1f))
            }
            // Near-uniform warm fill → valid red/orange tile, not banner.
        }

        // Partial dark overlay (50–70% class): dim cover without full-cell blackout.
        if (darkFrac >= partialDarkFractionThreshold) {
            return Result(true, "partial_dark", darkFrac.coerceIn(0f, 1f))
        }

        // Gray / white / washed blue UI chrome covering ≥ half the crop.
        if (uiFrac >= uiOverlayFractionThreshold) {
            return Result(true, "partial_ui_overlay", uiFrac.coerceIn(0f, 1f))
        }

        // Near-uniform very dark mean
        val meanL = PixelMath.meanLuma(cellPixels)
        if (meanL < darkLumaThreshold * 0.85f) {
            return Result(true, "mean_dark", 0.9f)
        }

        return Result(false, "clear", 1f - darkFrac)
    }

    private fun isBannerHue(h: Float): Boolean = h <= 45f || h >= 345f

    /**
     * UI chrome samples: gray/white (low sat, mid/high value) or light washed blue panels.
     * High-sat game tiles (incl. solid R/O/B) do not match.
     */
    private fun isUiOverlaySample(hsv: FloatArray): Boolean {
        val h = hsv[0]
        val s = hsv[1]
        val v = hsv[2]
        if (s < 0.22f && v > 0.45f) return true // gray / white
        // Washed blue UI strip (not saturated tile blue)
        if (h in 185f..250f && s in 0.12f..0.48f && v > 0.55f) return true
        return false
    }
}
