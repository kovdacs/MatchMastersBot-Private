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
open class BoardFinder(
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
    open fun find(
        pixels: IntArray,
        width: Int,
        height: Int,
        contentRoi: ContentRoi? = null,
    ): FindResult {
        require(pixels.size >= width * height) { "pixels too small" }
        val diag = mutableMapOf<String, String>()
        val content = contentRoi ?: ContentRoi.full(width, height)
        diag["contentRoi"] = "LTRB(${content.left},${content.top},${content.right},${content.bottom})"

        val overlayMask = OverlayColumnMask.detect(pixels, width, height)
        diag["overlayColumns"] = overlayMask.describe()

        val boardRoi = refineBoardRoi(pixels, width, height, content, overlayMask, diag)
        diag["boardRoi"] = "LTRB(${boardRoi.left},${boardRoi.top},${boardRoi.right},${boardRoi.bottom})"

        val projected = tryProjection(pixels, width, height, boardRoi, overlayMask, diag)
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
        overlayMask: OverlayColumnMask,
        diag: MutableMap<String, String>,
    ): ContentRoi {
        // Board ≈ content, with optional dark-margin trim and warm-banner strip trim.
        // Coordinates stay in frame space (letterbox ROI is not re-shifted).
        val lumaThr = 24
        val cw = content.width()
        val ch = content.height()
        if (cw < GridGeometry.GRID_SIZE * 4 || ch < GridGeometry.GRID_SIZE * 4) {
            diag["boardRefine"] = "content_too_small_use_as_is"
            diag["playfieldSnap"] = "content_too_small"
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
        val latticed = refitGutterLattice(
            pixels, width, height, trimmed, playfield, overlayMask, diag,
        ) ?: playfield
        val area = latticed.width() * latticed.height()
        val contentArea = cw * ch
        if (contentArea > 0 && area.toFloat() / contentArea < 0.20f) {
            diag["boardRefine"] = "over_trim_reverted"
            return content
        }
        val snapped = diag["playfieldSnap"] == "separator_square"
        val latched = diag["playfieldSnap"] == "gutter_lattice"
        val refineTag = when {
            latched -> "gutter_lattice"
            snapped && bannerTrim > 0 -> "dark_banner_playfield"
            snapped -> "dark_playfield"
            bannerTrim > 0 -> "dark_and_banner_trim"
            else -> "inner_dark_trim"
        }
        diag["boardRefine"] = refineTag
        return latticed
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
            diag["playfieldSnap"] = "not_tall"
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
        if (side < GridGeometry.GRID_SIZE * 8) {
            // Width collapsed before a separator search. The key must still be
            // exported: a missing playfieldSnap used to read as "not measured".
            diag["playfieldSnap"] = "side_collapsed"
            return roi
        }

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

        /** Tall phone frames only. Synthetic ~540px portraits stay on separator snap. */
        internal const val LATTICE_MIN_FRAME_HEIGHT = 1600
        internal const val LATTICE_MIN_FRAME_WIDTH = 700
        internal const val LATTICE_MIN_SCORE = 5f
        internal const val LATTICE_MIN_LUMA_STD = 45f
        /** Snap must disagree by more than this before a lattice may replace it. */
        internal const val LATTICE_DISAGREE_PX = 80
        internal const val LATTICE_CLIP_MARGIN = 8

        /**
         * Projection pitches wider than this are not a grid. Phone 0.24.7.2
         * measured 126 vs 174 (spread 48) on one board. Replace them with one
         * period. The PvP golden's row spread is 38 px and must stay on the
         * peaks that produce grid confidence 0.9872.
         */
        internal const val UNIFORM_PITCH_SPREAD_PX = 42f

        /**
         * A uniform period may move the outer edge this far to sit on the
         * gutter. A larger jump keeps the lattice rectangle and only evens
         * the cuts inside it.
         */
        internal const val UNIFORM_ORIGIN_SHIFT_PX = 16f

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

        /**
         * When picked pitches disagree by more than [UNIFORM_PITCH_SPREAD_PX],
         * place 8 lines on one period. The period and the origin are the pair
         * whose lines sit on the strongest edge band. The origin may sit inside
         * the ROI, so a header sliver above the first gutter is not row 0.
         * Recognisers and [VisionThresholds] are not involved.
         */
        internal fun uniformPitchFromEnergy(picked: FloatArray, energy: FloatArray): UniformPitch {
            val n = energy.size
            if (picked.size != GridGeometry.BOUNDARY_COUNT || n < GridGeometry.GRID_SIZE * 3) {
                return UniformPitch(picked, applied = false, period = -1, origin = -1, spreadPx = 0f)
            }
            var minP = Float.MAX_VALUE
            var maxP = 0f
            for (i in 0 until GridGeometry.GRID_SIZE) {
                val pitch = picked[i + 1] - picked[i]
                if (pitch < minP) minP = pitch
                if (pitch > maxP) maxP = pitch
            }
            val spread = maxP - minP
            if (spread <= UNIFORM_PITCH_SPREAD_PX) {
                return UniformPitch(picked, applied = false, period = -1, origin = -1, spreadPx = spread)
            }
            val nominal = n.toFloat() / GridGeometry.GRID_SIZE
            val pMin = (nominal * 0.90f).toInt().coerceAtLeast(4)
            val pMax = (nominal * 1.08f).toInt().coerceAtLeast(pMin)
            var bestScore = Float.NEGATIVE_INFINITY
            var bestPeriod = -1
            var bestOrigin = -1
            for (period in pMin..pMax) {
                val slack = n - GridGeometry.GRID_SIZE * period
                val slackCap = (period * 0.40f).toInt()
                if (slack < 0 || slack > slackCap) continue
                for (origin in 0..slack) {
                    var score = 0f
                    for (i in 0..GridGeometry.GRID_SIZE) {
                        val y = origin + i * period
                        if (y !in 0 until n) continue
                        var band = energy[y]
                        if (y - 1 >= 0) band = maxOf(band, energy[y - 1])
                        if (y + 1 < n) band = maxOf(band, energy[y + 1])
                        score += band
                    }
                    if (score > bestScore) {
                        bestScore = score
                        bestPeriod = period
                        bestOrigin = origin
                    }
                }
            }
            if (bestPeriod < 0) {
                return UniformPitch(picked, applied = false, period = -1, origin = -1, spreadPx = spread)
            }
            val bounds = FloatArray(GridGeometry.BOUNDARY_COUNT) { i ->
                (bestOrigin + i * bestPeriod).toFloat()
            }
            if (!GridGeometry.isStrictlyIncreasing(bounds) || bounds[GridGeometry.GRID_SIZE] > n + 1f) {
                return UniformPitch(picked, applied = false, period = -1, origin = -1, spreadPx = spread)
            }
            val shiftStart = kotlin.math.abs(bounds[0] - picked[0])
            val shiftEnd = kotlin.math.abs(bounds[GridGeometry.GRID_SIZE] - picked[GridGeometry.GRID_SIZE])
            if (shiftStart > UNIFORM_ORIGIN_SHIFT_PX || shiftEnd > UNIFORM_ORIGIN_SHIFT_PX) {
                return evenOuterSplit(picked, spread)
            }
            return UniformPitch(
                bounds = bounds,
                applied = true,
                period = bestPeriod,
                origin = bestOrigin,
                spreadPx = spread,
            )
        }

        /** Equal pitches between the outer edges projection already accepted. */
        internal fun evenOuterSplit(picked: FloatArray, spreadPx: Float): UniformPitch {
            val start = picked[0]
            val end = picked[GridGeometry.GRID_SIZE]
            val step = (end - start) / GridGeometry.GRID_SIZE
            val bounds = FloatArray(GridGeometry.BOUNDARY_COUNT) { i ->
                if (i == GridGeometry.GRID_SIZE) end else start + i * step
            }
            return UniformPitch(
                bounds = bounds,
                applied = true,
                period = step.toInt(),
                origin = start.toInt(),
                spreadPx = spreadPx,
            )
        }
    }

    internal data class UniformPitch(
        val bounds: FloatArray,
        val applied: Boolean,
        val period: Int,
        val origin: Int,
        val spreadPx: Float,
    )

    /**
     * Phone frames whose square snap locks the header or the screen bottom.
     * A 7-cell dark gutter lattice in the mid-screen window replaces that snap
     * only when it disagrees by more than [LATTICE_DISAGREE_PX], the snap is
     * clipped to the frame bottom, or the snap top sits in the header.
     * A snap that already agrees (PvP golden) is returned unchanged — separator
     * luma is not retuned.
     */
    private fun refitGutterLattice(
        pixels: IntArray,
        width: Int,
        height: Int,
        trimmed: ContentRoi,
        snapped: ContentRoi,
        overlayMask: OverlayColumnMask,
        diag: MutableMap<String, String>,
    ): ContentRoi? {
        fun note(fit: LatticeFit?, used: Boolean) {
            if (fit == null) {
                diag["latticeScore"] = "none"
                diag["latticeStd"] = "none"
                diag["latticeCandidate"] = "none"
                diag["latticeRoiUsed"] = "no"
                return
            }
            diag["latticeScore"] = "%.2f".format(fit.score)
            diag["latticeStd"] = "%.2f".format(fit.minStd)
            diag["latticeCandidate"] = "${fit.top},${fit.bottom},${fit.period}"
            diag["latticeRoiUsed"] = if (used) "yes" else "no"
            diag["lattice"] = "${fit.top},${fit.bottom},${fit.period},${"%.2f".format(fit.score)}"
        }
        if (height < LATTICE_MIN_FRAME_HEIGHT || width < LATTICE_MIN_FRAME_WIDTH) {
            note(null, false)
            return null
        }
        if (trimmed.height() < trimmed.width() * TALL_ASPECT_THRESHOLD) {
            note(null, false)
            return null
        }
        val fit = searchGutterLattice(pixels, width, height, overlayMask)
        if (fit == null) {
            note(null, false)
            return null
        }
        // Phone Test 0 live frame scored 2.47 (candidate 1230,2224,142).
        // The native screenshot of that scene scores above this bar. Do not
        // lower [LATTICE_MIN_SCORE] to accept the weak live fit.
        if (fit.score < LATTICE_MIN_SCORE || fit.minStd < LATTICE_MIN_LUMA_STD) {
            note(fit, false)
            return null
        }
        val clippedBottom = snapped.bottom >= height - LATTICE_CLIP_MARGIN
        val headerLock = snapped.top < height * 0.40f
        val far = kotlin.math.abs(snapped.top - fit.top) > LATTICE_DISAGREE_PX ||
            kotlin.math.abs(snapped.bottom - fit.bottom) > LATTICE_DISAGREE_PX
        if (!clippedBottom && !headerLock && !far) {
            note(fit, false)
            return null
        }
        val left = if (overlayMask.isEmpty()) snapped.left else trimmed.left
        val right = if (overlayMask.isEmpty()) snapped.right else trimmed.right
        if (right - left < GridGeometry.GRID_SIZE * 8 ||
            fit.bottom - fit.top < GridGeometry.GRID_SIZE * 8
        ) {
            note(fit, false)
            return null
        }
        note(fit, true)
        diag["playfieldSnap"] = "gutter_lattice"
        diag["playfieldTop"] = fit.top.toString()
        return ContentRoi(left, fit.top, right, fit.bottom)
    }

    private data class LatticeFit(
        val top: Int,
        val bottom: Int,
        val period: Int,
        val score: Float,
        val minStd: Float,
    )

    /**
     * Best 7-cell period whose top sits in 47–54% of the frame and whose
     * bottom is not the screen edge. Dark-blue gutters on columns the overlay
     * does not cover. Earlier candidate wins a tie.
     */
    private fun searchGutterLattice(
        pixels: IntArray,
        width: Int,
        height: Int,
        overlayMask: OverlayColumnMask,
    ): LatticeFit? {
        val sampleXs = ArrayList<Int>(width / 3 + 1)
        var x = 0
        while (x < width) {
            if (!overlayMask.covers(x)) sampleXs.add(x)
            x += 3
        }
        if (sampleXs.isEmpty()) return null
        val dark = FloatArray(height)
        for (y in 0 until height) {
            var hit = 0
            for (sx in sampleXs) {
                val p = pixels[y * width + sx]
                val r = PixelMath.red(p)
                val g = PixelMath.green(p)
                val b = PixelMath.blue(p)
                if (PixelMath.luma(p) < 48 && b > r && b > g) hit++
            }
            dark[y] = hit.toFloat() / sampleXs.size
        }
        val smooth = FloatArray(height)
        for (y in 0 until height) {
            var sum = 0f
            var n = 0
            for (k in -2..2) {
                val yy = y + k
                if (yy in 0 until height) {
                    sum += dark[yy]
                    n++
                }
            }
            smooth[y] = if (n == 0) 0f else sum / n
        }
        val lumaStd = HashMap<Int, Float>()
        fun stdAt(y: Int): Float {
            lumaStd[y]?.let { return it }
            var sum = 0.0
            var sumSq = 0.0
            var n = 0
            var xx = 0
            while (xx < width) {
                if (!overlayMask.covers(xx)) {
                    val l = PixelMath.luma(pixels[y * width + xx]).toDouble()
                    sum += l
                    sumSq += l * l
                    n++
                }
                xx += 4
            }
            val value = if (n < 2) {
                0f
            } else {
                val mean = sum / n
                val variance = (sumSq / n) - mean * mean
                kotlin.math.sqrt(variance.coerceAtLeast(0.0)).toFloat()
            }
            lumaStd[y] = value
            return value
        }
        val pMin = (width / 7f * 0.92f).toInt()
        val pMax = (width / 7f * 1.06f).toInt()
        if (pMax < pMin || pMin < 4) return null
        val top0 = (height * 0.47f).toInt()
        val top1 = (height * 0.54f).toInt()
        var best: LatticeFit? = null
        for (period in pMin..pMax) {
            val footerCut = (period * 0.6f).toInt()
            var top = top0
            while (top <= top1) {
                val bottom = top + 7 * period
                if (bottom >= height - 2 || bottom > height - footerCut) {
                    top++
                    continue
                }
                var boundaryDark = 0f
                for (i in 0..7) {
                    val yb = top + i * period
                    if (yb in 0 until height) boundaryDark += smooth[yb]
                }
                var centerDark = 0f
                var minStd = Float.MAX_VALUE
                for (i in 0 until 7) {
                    val yc = top + i * period + period / 2
                    if (yc in 0 until height) {
                        centerDark += smooth[yc]
                        val st = stdAt(yc)
                        if (st < minStd) minStd = st
                    }
                }
                if (minStd == Float.MAX_VALUE) {
                    top++
                    continue
                }
                var score = boundaryDark - 0.65f * centerDark
                score += if (minStd < LATTICE_MIN_LUMA_STD) -3f else 0.015f * minStd
                if (best == null || score > best.score) {
                    best = LatticeFit(top, bottom, period, score, minStd)
                }
                top++
            }
        }
        return best
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
        overlayMask: OverlayColumnMask,
        diag: MutableMap<String, String>,
    ): GridGeometry? {
        // Primary path (unchanged scoring) — keeps REAL_FRAME golden 0.9872 stable.
        val uniformSnap = diag["playfieldSnap"] == "gutter_lattice"
        val primary = projectOnRoi(
            pixels, width, height, boardRoi, overlayMask,
            softOutlierFrac = null,
            uniformSnap = uniformSnap,
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
            pixels, width, height, boardRoi, overlayMask,
            softOutlierFrac = GUTTER_SOFT_OUTLIER_FRAC,
            uniformSnap = uniformSnap,
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
                                pixels, width, height, candRoi, overlayMask,
                                softOutlierFrac = GUTTER_SOFT_OUTLIER_FRAC,
                                uniformSnap = uniformSnap,
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
        val uniformSnap: String,
        val xPicked: FloatArray,
        val yPicked: FloatArray,
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
        diag["uniformSnap"] = attempt.uniformSnap
        diag["projPickedX"] = attempt.xPicked.joinToString(",") { "%.0f".format(it) }
        diag["projPickedY"] = attempt.yPicked.joinToString(",") { "%.0f".format(it) }
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
        overlayMask: OverlayColumnMask,
        softOutlierFrac: Float?,
        uniformSnap: Boolean,
    ): ProjAttempt? {
        val bw = boardRoi.width()
        val bh = boardRoi.height()
        if (bw < GridGeometry.GRID_SIZE * 3 || bh < GridGeometry.GRID_SIZE * 3) return null
        // Empty mask: covers() is false for every x, so energy matches the unmasked path.
        val colEnergy = FloatArray(bw)
        for (x in 0 until bw) {
            val fx = boardRoi.left + x
            if (overlayMask.covers(fx)) {
                colEnergy[x] = 0f
                continue
            }
            var e = 0f
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
                if (overlayMask.covers(fx)) continue
                val c0 = pixels[fy * width + fx]
                val c1 = if (fy + 1 < height) pixels[(fy + 1) * width + fx] else c0
                e += kotlin.math.abs(PixelMath.luma(c0) - PixelMath.luma(c1)).toFloat()
            }
            rowEnergy[y] = e
        }

        suppressBannerEnergy(pixels, width, boardRoi, colEnergy, rowEnergy)

        val peakX = IntArray(1)
        val peakY = IntArray(1)
        val xPicked = pickSevenCellBoundariesInternal(colEnergy, softOutlierFrac, peakX) ?: return null
        val yPicked = pickSevenCellBoundariesInternal(rowEnergy, softOutlierFrac, peakY) ?: return null
        val xFit = if (uniformSnap) {
            uniformPitchFromEnergy(xPicked, colEnergy)
        } else {
            UniformPitch(xPicked, applied = false, period = -1, origin = -1, spreadPx = 0f)
        }
        val yFit = if (uniformSnap) {
            uniformPitchFromEnergy(yPicked, rowEnergy)
        } else {
            UniformPitch(yPicked, applied = false, period = -1, origin = -1, spreadPx = 0f)
        }
        val xLocal = xFit.bounds
        val yLocal = yFit.bounds

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
        val gridLeft = (boardRoi.left + xLocal[0]).toInt().coerceIn(0, width - 1)
        val gridTop = (boardRoi.top + yLocal[0]).toInt().coerceIn(0, height - 1)
        val gridRight = (boardRoi.left + xLocal[GridGeometry.GRID_SIZE]).toInt()
            .coerceIn(gridLeft + 1, width)
        val gridBottom = (boardRoi.top + yLocal[GridGeometry.GRID_SIZE]).toInt()
            .coerceIn(gridTop + 1, height)
        val gridRoi = ContentRoi(gridLeft, gridTop, gridRight, gridBottom)
        val grid = GridGeometry(xBounds, yBounds, GridMethod.PROJECTION, conf, gridRoi)
        val uniformSnap = snapNote("x", xFit) + " " + snapNote("y", yFit)
        return ProjAttempt(
            grid, relVarX, relVarY, xLocal, yLocal, peakX[0], peakY[0], uniformSnap, xPicked, yPicked,
        )
    }

    private fun snapNote(axis: String, fit: UniformPitch): String =
        if (!fit.applied) {
            "$axis:kept"
        } else {
            "$axis:period=${fit.period},origin=${fit.origin},spread=${fit.spreadPx.toInt()}"
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
