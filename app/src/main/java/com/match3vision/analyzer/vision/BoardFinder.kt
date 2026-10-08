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

        // Fail-closed: 0.72 < MIN_GRID_CONFIDENCE (0.98) → VisionValidator HOLD.
        // Do NOT raise this to pass the gate; fix ROI/projection/gutters instead.
        val fallback = GridGeometry.evenSplit(boardRoi, confidence = 0.72f)
        diag["method"] = GridMethod.EVEN_SPLIT.name
        diag["fallback"] = "EVEN_SPLIT"
        diag["fallbackFailClosed"] = "0.72<MIN_GRID_0.98"
        return FindResult(fallback, diag)
    }

    /**
     * Prefer content ROI; optionally shrink away from near-black margins still inside content,
     * then (on tall portrait UI frames) snap to a near-square 7×7 playfield below the
     * dark board separator so header/timer/RULES/toolbar are excluded.
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

        val trimmed = ContentRoi(left, top, right, bottom)
        val playfield = snapSquarePlayfield(pixels, width, height, trimmed, diag)
        val area = playfield.width() * playfield.height()
        val contentArea = cw * ch
        if (contentArea > 0 && area.toFloat() / contentArea < 0.20f) {
            diag["boardRefine"] = "over_trim_reverted"
            return content
        }
        val snapped = diag["playfieldSnap"] == "separator_square"
        val refineTag = when {
            snapped && bannerTrim > 0 -> "dark_banner_playfield"
            snapped -> "dark_playfield"
            bannerTrim > 0 -> "dark_and_banner_trim"
            else -> "inner_dark_trim"
        }
        diag["boardRefine"] = refineTag
        return playfield
    }

    /**
     * On tall portrait frames (header + board + footer), the 7×7 gem grid is a near-square
     * region below a dark purple separator under the timer. Snap ROI to that square so
     * projection/EVEN_SPLIT are not diluted by UI chrome.
     *
     * No-op when content is already board-like (aspect ≤ [TALL_ASPECT_THRESHOLD]) — keeps
     * synthetic letterboxed unit fixtures unchanged.
     */
    private fun snapSquarePlayfield(
        pixels: IntArray,
        width: Int,
        height: Int,
        roi: ContentRoi,
        diag: MutableMap<String, String>,
    ): ContentRoi {
        val cw = roi.width()
        val ch = roi.height()
        if (cw < GridGeometry.GRID_SIZE * 8 || ch < cw * TALL_ASPECT_THRESHOLD) {
            return roi
        }

        // Side rim: darker purple board margin inside mid-luma side chrome.
        // Probe the lower-middle band (typical playfield), not the avatar header.
        var left = roi.left
        var right = roi.right
        val midTop = roi.top + (ch * 45) / 100
        val midBottom = (roi.top + (ch * 75) / 100).coerceAtMost(roi.bottom)
        if (midBottom > midTop + GridGeometry.GRID_SIZE * 4) {
            val rimThr = 32f
            val left0 = left
            while (left < right - 1 && colMeanLuma(pixels, width, left, midTop, midBottom) >= rimThr) {
                left++
            }
            if (left >= right - GridGeometry.GRID_SIZE * 4 ||
                colMeanLuma(pixels, width, left, midTop, midBottom) >= rimThr
            ) {
                left = left0
            }
            var r = right - 1
            while (r > left + 1 && colMeanLuma(pixels, width, r, midTop, midBottom) >= rimThr) {
                r--
            }
            if (r <= left + GridGeometry.GRID_SIZE * 4 ||
                colMeanLuma(pixels, width, r, midTop, midBottom) >= rimThr
            ) {
                right = roi.right
            } else {
                right = r + 1
            }
        }
        val side = right - left
        if (side < GridGeometry.GRID_SIZE * 8) return roi

        val sepBottom = findDarkSeparatorBottom(pixels, width, left, right, roi.top, roi.bottom)
        if (sepBottom == null) {
            diag["playfieldSnap"] = "no_separator"
            return roi
        }

        // Skip thin board-chrome / empty padding under the separator (~½ cell).
        // Without this, EVEN_SPLIT/PROJECTION lock onto the frame border instead of gem gutters.
        val chromeInset = (side / 14).coerceAtLeast(1)
        var top = (sepBottom + chromeInset).coerceAtMost(roi.bottom - side)
        if (top < sepBottom) top = sepBottom

        var bottom = top + side
        if (bottom > roi.bottom) {
            // Prefer keeping square height by shifting up if footer clips
            val shift = bottom - roi.bottom
            top = (top - shift).coerceAtLeast(sepBottom)
            bottom = top + side
            if (bottom > roi.bottom) {
                bottom = roi.bottom
            }
        }
        if (bottom - top < side * 9 / 10) {
            diag["playfieldSnap"] = "square_too_short"
            return roi
        }

        diag["playfieldSnap"] = "separator_square"
        diag["playfieldSepBottom"] = sepBottom.toString()
        diag["playfieldTop"] = top.toString()
        return ContentRoi(left, top, right, bottom)
    }

    /**
     * Dark purple horizontal separator under the PvP timer (near-full width, very low luma).
     * Returns the first row *below* the darkest qualifying run in the upper half of [roi].
     */
    private fun findDarkSeparatorBottom(
        pixels: IntArray,
        width: Int,
        left: Int,
        right: Int,
        roiTop: Int,
        roiBottom: Int,
    ): Int? {
        val full = findDarkSeparatorBottomInset(
            pixels, width, left, right, roiTop, roiBottom, horizontalInsetFrac = 0f,
        )
        if (full != null) return full
        // Overlay / bubble on the far side can raise full-width row luma and hide the
        // purple timer separator — retry on the horizontal center band only.
        return findDarkSeparatorBottomInset(
            pixels, width, left, right, roiTop, roiBottom,
            horizontalInsetFrac = SEPARATOR_CENTER_INSET_FRAC,
        )
    }

    private fun findDarkSeparatorBottomInset(
        pixels: IntArray,
        width: Int,
        left: Int,
        right: Int,
        roiTop: Int,
        roiBottom: Int,
        horizontalInsetFrac: Float,
    ): Int? {
        val h = roiBottom - roiTop
        if (h < 32) return null
        val span = right - left
        val inset = (span * horizontalInsetFrac).toInt().coerceAtLeast(0)
        val sampleLeft = (left + inset).coerceAtMost(right - 1)
        val sampleRight = (right - inset).coerceAtLeast(sampleLeft + 1)
        if (sampleRight - sampleLeft < GridGeometry.GRID_SIZE * 4) return null
        val searchEnd = roiTop + (h * 55) / 100
        val minRun = 12
        val lumaCut = 22f
        var bestMean = Float.MAX_VALUE
        var bestEnd = -1
        var y = roiTop + h / 12 // skip extreme top status chrome
        while (y < searchEnd) {
            val luma = rowMeanLuma(pixels, width, y, sampleLeft, sampleRight)
            if (luma < lumaCut &&
                rowBlueishFraction(pixels, width, y, sampleLeft, sampleRight) > 0.75f
            ) {
                val y0 = y
                var sum = 0f
                var n = 0
                while (y < searchEnd &&
                    rowMeanLuma(pixels, width, y, sampleLeft, sampleRight) < lumaCut &&
                    rowBlueishFraction(pixels, width, y, sampleLeft, sampleRight) > 0.75f
                ) {
                    sum += rowMeanLuma(pixels, width, y, sampleLeft, sampleRight)
                    n++
                    y++
                }
                if (n >= minRun) {
                    val mean = sum / n
                    if (mean < bestMean) {
                        bestMean = mean
                        bestEnd = y0 + n // first row below run
                    }
                }
            } else {
                y++
            }
        }
        return if (bestEnd > 0) bestEnd else null
    }

    /** Fraction of sampled pixels with blue channel dominant (purple/cyan UI / board chrome). */
    private fun rowBlueishFraction(
        pixels: IntArray,
        width: Int,
        y: Int,
        left: Int,
        right: Int,
    ): Float {
        var hit = 0
        var n = 0
        var x = left
        val step = PixelMath.sampleStep(right - left, 32)
        while (x < right) {
            val p = pixels[y * width + x]
            val r = PixelMath.red(p)
            val g = PixelMath.green(p)
            val b = PixelMath.blue(p)
            val luma = PixelMath.luma(p)
            if (luma < 60 && b > r && b > g) hit++
            n++
            x += step
        }
        return if (n == 0) 0f else hit.toFloat() / n
    }

    companion object {
        /** Content taller than this × width is treated as portrait UI+board (snap playfield). */
        const val TALL_ASPECT_THRESHOLD = 1.25f

        /**
         * Max |peak−ideal| / period before a gutter is treated as an offset outlier
         * (gem-interior edge locking onto the wrong column/row).
         */
        internal const val GUTTER_OFFSET_OUTLIER_FRAC = 0.12f

        /**
         * Milder offset fraction used only on live recovery when first-pass gridConf
         * is below [VisionThresholds.MIN_GRID_CONFIDENCE] (keeps clean REAL_FRAME path
         * unchanged so golden 0.9872 / boundaries stay stable).
         */
        internal const val GUTTER_SOFT_OUTLIER_FRAC = 0.06f

        /**
         * Peak energy / median-peak above this → energy outlier (e.g. toolbar spike).
         */
        internal const val GUTTER_ENERGY_OUTLIER_MULT = 2.5f

        /**
         * Horizontal inset (each side) when full-width dark-separator search fails —
         * resists top-end / side overlay panels covering the timer separator.
         */
        internal const val SEPARATOR_CENTER_INSET_FRAC = 0.40f

        /**
         * ±px micro-search of board ROI when soft gutter re-pick still < MIN_GRID.
         */
        internal const val LIVE_ROI_NUDGE_PX = 14

        /**
         * 7-cell boundary picker (local ROI coords).
         *
         * Pass 1: absolute-max energy in the period search window (true gutters).
         * Pass 2: if a peak is an **offset outlier** (|off| > [GUTTER_OFFSET_OUTLIER_FRAC]·period)
         * or an **energy outlier** (> [GUTTER_ENERGY_OUTLIER_MULT]× median peak energy),
         * or (when [softOutlierFrac] is set) a **soft offset** (|off| > soft·period),
         * re-pick the strong local-max nearest the ideal inside a tighter ±offset band,
         * preferring typical-energy peaks (rejects toolbar / false gem edges).
         *
         * @param softOutlierFrac optional milder offset gate for live recovery only.
         */
        internal fun pickSevenCellBoundariesInternal(
            energy: FloatArray,
            softOutlierFrac: Float? = null,
            strongPeakCountOut: IntArray? = null,
        ): FloatArray? {
            val n = energy.size
            if (n < GridGeometry.GRID_SIZE * 3) return null
            val period = n.toFloat() / GridGeometry.GRID_SIZE
            val bounds = FloatArray(GridGeometry.BOUNDARY_COUNT)
            bounds[0] = 0f
            bounds[GridGeometry.GRID_SIZE] = n.toFloat()

            val meanE = energy.average().toFloat()
            val strongThr = meanE * 1.25f
            val searchRadius = (period * 0.35f).toInt().coerceAtLeast(1)
            val clampRadius = (period * GUTTER_OFFSET_OUTLIER_FRAC).toInt().coerceAtLeast(1)

            // Pass 1: absolute-max peaks
            val raw = IntArray(GridGeometry.GRID_SIZE + 1)
            val peakE = FloatArray(GridGeometry.GRID_SIZE + 1)
            var strongPeaks = 0
            for (g in 1 until GridGeometry.GRID_SIZE) {
                val ideal = (g * period).toInt().coerceIn(0, n - 1)
                val from = (ideal - searchRadius).coerceAtLeast(1)
                val to = (ideal + searchRadius).coerceAtMost(n - 2)
                var absX = ideal
                var absE = -1f
                for (x in from..to) {
                    val e = energy[x]
                    if (e > absE) {
                        absE = e
                        absX = x
                    }
                }
                if (absE >= strongThr && absE > 1f) {
                    strongPeaks++
                    raw[g] = absX
                    peakE[g] = absE
                } else {
                    raw[g] = ideal
                    peakE[g] = 0f
                }
            }
            if (strongPeakCountOut != null && strongPeakCountOut.isNotEmpty()) {
                strongPeakCountOut[0] = strongPeaks
            }
            if (strongPeaks < 4) return null

            var energySum = 0f
            var energyN = 0
            for (g in 1 until GridGeometry.GRID_SIZE) {
                if (peakE[g] > 0f) {
                    energySum += peakE[g]
                    energyN++
                }
            }
            // median via sort of copy of positive peaks
            val positives = FloatArray(energyN)
            var pi = 0
            for (g in 1 until GridGeometry.GRID_SIZE) {
                if (peakE[g] > 0f) positives[pi++] = peakE[g]
            }
            positives.sort()
            val medianPeak = when {
                energyN == 0 -> meanE
                energyN % 2 == 1 -> positives[energyN / 2]
                else -> 0.5f * (positives[energyN / 2 - 1] + positives[energyN / 2])
            }
            val energyOutlierThr = medianPeak * GUTTER_ENERGY_OUTLIER_MULT

            // Pass 2: re-pick outliers toward ideal period
            for (g in 1 until GridGeometry.GRID_SIZE) {
                val ideal = (g * period).toInt().coerceIn(0, n - 1)
                val peak = raw[g]
                val off = kotlin.math.abs(peak - ideal)
                val softRadius = softOutlierFrac?.let { frac ->
                    (period * frac).toInt().coerceAtLeast(1)
                }
                val outlier = off > clampRadius ||
                    (peakE[g] > energyOutlierThr && peakE[g] > 0f) ||
                    (softRadius != null && off > softRadius)
                if (!outlier) {
                    bounds[g] = peak.toFloat()
                    continue
                }
                val from = (ideal - clampRadius).coerceAtLeast(1)
                val to = (ideal + clampRadius).coerceAtMost(n - 2)
                var bestX = -1
                var bestDist = Int.MAX_VALUE
                var bestE = -1f
                // Prefer typical-energy local maxima nearest ideal
                for (x in from..to) {
                    val e = energy[x]
                    if (e < strongThr || e <= 1f) continue
                    if (e < energy[x - 1] || e < energy[x + 1]) continue
                    if (e > energyOutlierThr) continue
                    val dist = kotlin.math.abs(x - ideal)
                    if (dist < bestDist || (dist == bestDist && e > bestE)) {
                        bestDist = dist
                        bestE = e
                        bestX = x
                    }
                }
                if (bestX < 0) {
                    // Relax energy cap; still stay inside clamp band
                    for (x in from..to) {
                        val e = energy[x]
                        if (e < strongThr || e <= 1f) continue
                        if (e < energy[x - 1] || e < energy[x + 1]) continue
                        val dist = kotlin.math.abs(x - ideal)
                        if (dist < bestDist || (dist == bestDist && e > bestE)) {
                            bestDist = dist
                            bestE = e
                            bestX = x
                        }
                    }
                }
                bounds[g] = if (bestX >= 0) bestX.toFloat() else ideal.toFloat()
            }

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
        // Primary path (unchanged scoring) — keeps REAL_FRAME golden 0.9872 stable.
        val primary = projectOnRoi(
            pixels, width, height, boardRoi, softOutlierFrac = null,
        ) ?: return null
        writeProjectionDiag(diag, primary)
        if (primary.grid.confidence >= VisionThresholds.MIN_GRID_CONFIDENCE) {
            diag["gridRecover"] = "none"
            return primary.grid
        }

        // Live recovery: mild ROI mis-snap (bubble / letterbox / side-chrome) often lands
        // gridConf≈0.972 with one soft-offset gutter. Soft re-pick clears MIN_GRID without
        // touching the clean first-pass used by REAL_FRAME.
        val soft = projectOnRoi(
            pixels, width, height, boardRoi, softOutlierFrac = GUTTER_SOFT_OUTLIER_FRAC,
        )
        var best = primary
        if (soft != null && soft.grid.confidence > best.grid.confidence) {
            best = soft
            diag["gridRecover"] = "soft_outlier"
            writeProjectionDiag(diag, best)
        }
        if (best.grid.confidence >= VisionThresholds.MIN_GRID_CONFIDENCE) {
            return best.grid
        }

        // Second recovery: micro-nudge square playfield and re-project with soft gutters.
        val side = minOf(boardRoi.width(), boardRoi.height())
        if (side >= GridGeometry.GRID_SIZE * 8) {
            var nudged: ProjAttempt? = null
            val step = 2
            var done = false
            var dy = -LIVE_ROI_NUDGE_PX
            while (!done && dy <= LIVE_ROI_NUDGE_PX) {
                var dx = -LIVE_ROI_NUDGE_PX
                while (!done && dx <= LIVE_ROI_NUDGE_PX) {
                    if (dx != 0 || dy != 0) {
                        val left = boardRoi.left + dx
                        val top = boardRoi.top + dy
                        val right = left + side
                        val bottom = top + side
                        if (left >= 0 && top >= 0 && right <= width && bottom <= height) {
                            val candRoi = ContentRoi(left, top, right, bottom)
                            val cand = projectOnRoi(
                                pixels, width, height, candRoi,
                                softOutlierFrac = GUTTER_SOFT_OUTLIER_FRAC,
                            )
                            if (cand != null &&
                                (nudged == null || cand.grid.confidence > nudged.grid.confidence)
                            ) {
                                nudged = cand
                                if (cand.grid.confidence >= 0.99f) {
                                    done = true
                                }
                            }
                        }
                    }
                    dx += step
                }
                dy += step
            }
            if (nudged != null && nudged.grid.confidence > best.grid.confidence) {
                best = nudged
                diag["gridRecover"] = "soft_outlier_roi_nudge"
                diag["boardRoi"] =
                    "LTRB(${best.grid.boardRoi.left},${best.grid.boardRoi.top}," +
                        "${best.grid.boardRoi.right},${best.grid.boardRoi.bottom})"
                writeProjectionDiag(diag, best)
            }
        }
        if (diag["gridRecover"] == null) diag["gridRecover"] = "none"
        return best.grid
    }

    private data class ProjAttempt(
        val grid: GridGeometry,
        val relVarX: Float,
        val relVarY: Float,
        val xLocal: FloatArray,
        val yLocal: FloatArray,
        val peakCountX: Int,
        val peakCountY: Int,
    )

    private fun writeProjectionDiag(diag: MutableMap<String, String>, attempt: ProjAttempt) {
        val bw = attempt.grid.boardRoi.width()
        val bh = attempt.grid.boardRoi.height()
        diag["projRelVarX"] = "%.4f".format(attempt.relVarX)
        diag["projRelVarY"] = "%.4f".format(attempt.relVarY)
        diag["projPeakCountX"] = attempt.peakCountX.toString()
        diag["projPeakCountY"] = attempt.peakCountY.toString()
        val periodX = bw.toFloat() / GridGeometry.GRID_SIZE
        val periodY = bh.toFloat() / GridGeometry.GRID_SIZE
        diag["projGuttersX"] = (1 until GridGeometry.GRID_SIZE).joinToString(",") { g ->
            val ideal = g * periodX
            val off = attempt.xLocal[g] - ideal
            "%d:%+.0f".format(g, off)
        }
        diag["projGuttersY"] = (1 until GridGeometry.GRID_SIZE).joinToString(",") { g ->
            val ideal = g * periodY
            val off = attempt.yLocal[g] - ideal
            "%d:%+.0f".format(g, off)
        }
    }

    /**
     * Projection on a fixed board ROI. [softOutlierFrac] null = production first pass
     * (REAL_FRAME golden); non-null = live recovery soft gutter re-pick.
     */
    private fun projectOnRoi(
        pixels: IntArray,
        width: Int,
        height: Int,
        boardRoi: ContentRoi,
        softOutlierFrac: Float?,
    ): ProjAttempt? {
        val bw = boardRoi.width()
        val bh = boardRoi.height()
        if (bw < GridGeometry.GRID_SIZE * 3 || bh < GridGeometry.GRID_SIZE * 3) return null

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

        suppressBannerEnergy(pixels, width, boardRoi, colEnergy, rowEnergy)

        val peakX = IntArray(1)
        val peakY = IntArray(1)
        val xLocal = pickSevenCellBoundariesInternal(colEnergy, softOutlierFrac, peakX) ?: return null
        val yLocal = pickSevenCellBoundariesInternal(rowEnergy, softOutlierFrac, peakY) ?: return null

        // ROI origin is added here. xLocal/yLocal are ROI-relative; the grid is full-frame pixels.
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
        if (relVarX > maxRelVariance || relVarY > maxRelVariance) return null

        // *1.5f maps typical clean gutter variance to ≥ MIN_GRID without lowering the gate.
        val conf = (1f - (relVarX + relVarY) * 1.5f).coerceIn(projectionMinConfidence, 0.99f)
        val grid = GridGeometry(xBounds, yBounds, GridMethod.PROJECTION, conf, boardRoi)
        return ProjAttempt(grid, relVarX, relVarY, xLocal, yLocal, peakX[0], peakY[0])
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
     * Absolute-max peak search with offset/energy-outlier re-pick toward the
     * ideal period (see [pickSevenCellBoundariesInternal]).
     */
    private fun pickSevenCellBoundaries(energy: FloatArray): FloatArray? =
        pickSevenCellBoundariesInternal(energy, softOutlierFrac = null)

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
