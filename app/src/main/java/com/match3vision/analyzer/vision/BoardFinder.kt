package com.match3vision.analyzer.vision

import com.match3vision.analyzer.capture.ContentRoi

/**
 * Locates the 7×7 match-3 board and builds [GridGeometry].
 *
 * Strategy (Phase 2 brief):
 * 1. Use optional letterbox [ContentRoi] as content region
 * 2. Treat bright/content interior as board ROI (within content)
 * 3. **Primary:** projection-based 8×8 boundaries (row/col edge energy)
 * 4. **Fallback:** [GridMethod.EVEN_SPLIT] equal division of board ROI
 *
 * Pure Kotlin on ARGB [IntArray] — JVM-testable. Bitmap wrapper optional for device.
 */
class BoardFinder(
    private val maxRelVariance: Float = GridGeometry.DEFAULT_MAX_REL_VARIANCE,
    private val projectionMinConfidence: Float = 0.85f,
) {

    data class FindResult(
        val grid: GridGeometry,
        val diagnostics: MutableMap<String, String> = mutableMapOf(),
    )

    /**
     * @param contentRoi optional letterbox ROI in frame coordinates; if null, full frame.
     */
    fun find(
        pixels: IntArray,
        width: Int,
        height: Int,
        contentRoi: ContentRoi? = null,
    ): FindResult {
        require(pixels.size >= width * height) { "pixels too small" }
        val diag = mutableMapOf<String, String>()
        val content = contentRoi ?: ContentRoi.full(width, height)
        diag["contentRoi"] = "LTRB(${content.left},${content.top},${content.right},${content.bottom})"

        val boardRoi = refineBoardRoi(pixels, width, height, content, diag)
        diag["boardRoi"] = "LTRB(${boardRoi.left},${boardRoi.top},${boardRoi.right},${boardRoi.bottom})"

        val projected = tryProjection(pixels, width, height, boardRoi, diag)
        if (projected != null && projected.validate(maxRelVariance)) {
            diag["method"] = GridMethod.PROJECTION.name
            return FindResult(projected, diag)
        }
        if (projected != null) {
            diag["projectionRejected"] = "monotonic_or_spacing_failed"
        } else {
            diag["projectionRejected"] = "no_peaks"
        }

        val fallback = GridGeometry.evenSplit(boardRoi, confidence = 0.72f)
        diag["method"] = GridMethod.EVEN_SPLIT.name
        diag["fallback"] = "EVEN_SPLIT"
        return FindResult(fallback, diag)
    }

    /**
     * Prefer content ROI; optionally shrink away from near-black margins still inside content.
     * Coordinates stay in **frame** space (letterbox does not shift relative geometry).
     */
    private fun refineBoardRoi(
        pixels: IntArray,
        width: Int,
        height: Int,
        content: ContentRoi,
        diag: MutableMap<String, String>,
    ): ContentRoi {
        // Board ≈ content, with optional dark-margin trim and warm-banner strip trim.
        // Coordinates stay in frame space (letterbox ROI is not re-shifted).
        val lumaThr = 24
        val cw = content.width()
        val ch = content.height()
        if (cw < GridGeometry.GRID_SIZE * 4 || ch < GridGeometry.GRID_SIZE * 4) {
            diag["boardRefine"] = "content_too_small_use_as_is"
            return content
        }

        var top = content.top
        var bottom = content.bottom
        var left = content.left
        var right = content.right

        // Trim remaining near-black strips inside content.
        while (top < bottom - 1 && rowMeanLuma(pixels, width, top, left, right) < lumaThr) top++
        while (bottom > top + 1 && rowMeanLuma(pixels, width, bottom - 1, left, right) < lumaThr) bottom--
        while (left < right - 1 && colMeanLuma(pixels, width, left, top, bottom) < lumaThr) left++
        while (right > left + 1 && colMeanLuma(pixels, width, right - 1, top, bottom) < lumaThr) right--

        // Trim warm orange/red banner bands so they do not shift the 7×7 grid.
        var bannerTrim = 0
        while (top < bottom - GridGeometry.GRID_SIZE * 3 &&
            rowWarmBannerFraction(pixels, width, top, left, right) > 0.50f
        ) {
            top++
            bannerTrim++
        }
        while (bottom > top + GridGeometry.GRID_SIZE * 3 &&
            rowWarmBannerFraction(pixels, width, bottom - 1, left, right) > 0.50f
        ) {
            bottom--
            bannerTrim++
        }
        if (bannerTrim > 0) diag["bannerTrimPx"] = bannerTrim.toString()

        val area = (right - left) * (bottom - top)
        val contentArea = cw * ch
        if (contentArea > 0 && area.toFloat() / contentArea < 0.45f) {
            diag["boardRefine"] = "over_trim_reverted"
            return content
        }
        diag["boardRefine"] = if (bannerTrim > 0) "dark_and_banner_trim" else "inner_dark_trim"
        return ContentRoi(left, top, right, bottom)
    }

    private fun rowWarmBannerFraction(
        pixels: IntArray,
        width: Int,
        y: Int,
        left: Int,
        right: Int,
    ): Float {
        val hsv = FloatArray(3)
        var warm = 0
        var n = 0
        var x = left
        val step = PixelMath.sampleStep(right - left, 32)
        while (x < right) {
            PixelMath.rgbToHsv(pixels[y * width + x], hsv)
            if (hsv[1] > 0.45f && hsv[2] > 0.35f && (hsv[0] <= 45f || hsv[0] >= 345f)) {
                warm++
            }
            n++
            x += step
        }
        return if (n == 0) 0f else warm.toFloat() / n
    }

    private fun tryProjection(
        pixels: IntArray,
        width: Int,
        height: Int,
        boardRoi: ContentRoi,
        diag: MutableMap<String, String>,
    ): GridGeometry? {
        val bw = boardRoi.width()
        val bh = boardRoi.height()
        if (bw < GridGeometry.GRID_SIZE * 3 || bh < GridGeometry.GRID_SIZE * 3) return null

        // Vertical gutters: project edge energy along X (sum of abs horiz gradient per column)
        val colEnergy = FloatArray(bw)
        for (x in 0 until bw) {
            var e = 0f
            val fx = boardRoi.left + x
            for (y in 0 until bh step PixelMath.sampleStep(bh)) {
                val fy = boardRoi.top + y
                val c0 = pixels[fy * width + fx]
                val c1 = if (fx + 1 < width) pixels[fy * width + fx + 1] else c0
                e += kotlin.math.abs(PixelMath.luma(c0) - PixelMath.luma(c1)).toFloat()
            }
            colEnergy[x] = e
        }

        val rowEnergy = FloatArray(bh)
        for (y in 0 until bh) {
            var e = 0f
            val fy = boardRoi.top + y
            for (x in 0 until bw step PixelMath.sampleStep(bw)) {
                val fx = boardRoi.left + x
                val c0 = pixels[fy * width + fx]
                val c1 = if (fy + 1 < height) pixels[(fy + 1) * width + fx] else c0
                e += kotlin.math.abs(PixelMath.luma(c0) - PixelMath.luma(c1)).toFloat()
            }
            rowEnergy[y] = e
        }

        // Suppress banner-like bright orange/red rows from creating false "bright row" peaks:
        // down-weight columns/rows that are mostly high-saturation warm hues at high luma.
        suppressBannerEnergy(pixels, width, boardRoi, colEnergy, rowEnergy)

        val xLocal = pickSevenCellBoundaries(colEnergy) ?: return null
        val yLocal = pickSevenCellBoundaries(rowEnergy) ?: return null

        val xBounds = FloatArray(GridGeometry.BOUNDARY_COUNT) { i ->
            boardRoi.left + xLocal[i]
        }
        val yBounds = FloatArray(GridGeometry.BOUNDARY_COUNT) { i ->
            boardRoi.top + yLocal[i]
        }

        if (!GridGeometry.isStrictlyIncreasing(xBounds) ||
            !GridGeometry.isStrictlyIncreasing(yBounds)
        ) {
            return null
        }
        val relVarX = GridGeometry.relativeSpacingVariance(xBounds)
        val relVarY = GridGeometry.relativeSpacingVariance(yBounds)
        diag["projRelVarX"] = "%.4f".format(relVarX)
        diag["projRelVarY"] = "%.4f".format(relVarY)
        if (relVarX > maxRelVariance || relVarY > maxRelVariance) return null

        // Confidence from spacing uniformity.
        // *1.5f maps typical clean gutter variance to ≥ MIN_GRID_CONFIDENCE without lowering the gate.
        val conf = (1f - (relVarX + relVarY) * 1.5f).coerceIn(projectionMinConfidence, 0.99f)
        return GridGeometry(xBounds, yBounds, GridMethod.PROJECTION, conf, boardRoi)
    }

    /**
     * Orange/red banner text must NOT create false bright-row peaks.
     * Down-weight energy where mean hue is warm and saturation high.
     */
    private fun suppressBannerEnergy(
        pixels: IntArray,
        width: Int,
        boardRoi: ContentRoi,
        colEnergy: FloatArray,
        rowEnergy: FloatArray,
    ) {
        val hsv = FloatArray(3)
        for (y in rowEnergy.indices) {
            var warm = 0
            var samples = 0
            val fy = boardRoi.top + y
            var x = 0
            while (x < boardRoi.width()) {
                val fx = boardRoi.left + x
                PixelMath.rgbToHsv(pixels[fy * width + fx], hsv)
                if (hsv[1] > 0.45f && hsv[2] > 0.35f && isWarmBannerHue(hsv[0])) warm++
                samples++
                x += PixelMath.sampleStep(boardRoi.width(), 32)
            }
            if (samples > 0 && warm.toFloat() / samples > 0.55f) {
                rowEnergy[y] *= 0.15f
            }
        }
        for (x in colEnergy.indices) {
            var warm = 0
            var samples = 0
            val fx = boardRoi.left + x
            var y = 0
            while (y < boardRoi.height()) {
                val fy = boardRoi.top + y
                PixelMath.rgbToHsv(pixels[fy * width + fx], hsv)
                if (hsv[1] > 0.45f && hsv[2] > 0.35f && isWarmBannerHue(hsv[0])) warm++
                samples++
                y += PixelMath.sampleStep(boardRoi.height(), 32)
            }
            if (samples > 0 && warm.toFloat() / samples > 0.55f) {
                colEnergy[x] *= 0.15f
            }
        }
    }

    private fun isWarmBannerHue(h: Float): Boolean =
        h <= 40f || h >= 350f // red–orange band

    /**
     * Pick 8 boundary positions in local [0, energy.size] for 7 cells.
     * Uses expected period + peak search near ideal gutter locations.
     */
    private fun pickSevenCellBoundaries(energy: FloatArray): FloatArray? {
        val n = energy.size
        if (n < GridGeometry.GRID_SIZE * 3) return null
        val period = n.toFloat() / GridGeometry.GRID_SIZE
        val bounds = FloatArray(GridGeometry.BOUNDARY_COUNT)
        bounds[0] = 0f
        bounds[GridGeometry.GRID_SIZE] = n.toFloat()

        val meanE = energy.average().toFloat()
        val searchRadius = (period * 0.35f).toInt().coerceAtLeast(1)
        var strongPeaks = 0
        for (g in 1 until GridGeometry.GRID_SIZE) {
            val ideal = (g * period).toInt().coerceIn(0, n - 1)
            val from = (ideal - searchRadius).coerceAtLeast(1)
            val to = (ideal + searchRadius).coerceAtMost(n - 2)
            var bestX = ideal
            var bestE = -1f
            for (x in from..to) {
                val e = energy[x]
                if (e > bestE) {
                    bestE = e
                    bestX = x
                }
            }
            // Significant gutter peak vs mean energy
            if (bestE >= meanE * 1.25f && bestE > 1f) {
                strongPeaks++
                bounds[g] = bestX.toFloat()
            } else {
                // Keep ideal slot but count as weak
                bounds[g] = ideal.toFloat()
            }
        }

        // Need a clear majority of internal gutters with real projection peaks
        if (strongPeaks < 4) return null

        for (i in 1 until bounds.size) {
            if (bounds[i] <= bounds[i - 1]) {
                bounds[i] = bounds[i - 1] + 1f
            }
        }
        if (bounds[GridGeometry.GRID_SIZE] > n) return null
        bounds[GridGeometry.GRID_SIZE] = n.toFloat()
        if (!GridGeometry.isStrictlyIncreasing(bounds)) return null
        return bounds
    }

    private fun rowMeanLuma(
        pixels: IntArray,
        width: Int,
        y: Int,
        left: Int,
        right: Int,
    ): Float {
        var sum = 0L
        var n = 0
        var x = left
        val step = PixelMath.sampleStep(right - left)
        while (x < right) {
            sum += PixelMath.luma(pixels[y * width + x])
            n++
            x += step
        }
        return if (n == 0) 0f else sum.toFloat() / n
    }

    private fun colMeanLuma(
        pixels: IntArray,
        width: Int,
        x: Int,
        top: Int,
        bottom: Int,
    ): Float {
        var sum = 0L
        var n = 0
        var y = top
        val step = PixelMath.sampleStep(bottom - top)
        while (y < bottom) {
            sum += PixelMath.luma(pixels[y * width + x])
            n++
            y += step
        }
        return if (n == 0) 0f else sum.toFloat() / n
    }
}
