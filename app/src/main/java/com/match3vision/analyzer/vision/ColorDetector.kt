package com.match3vision.analyzer.vision

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Center-weighted robust color recognition on a cell crop.
 *
 * Samples an ellipse around the cell center (default radius ≈ 28% of cell
 * half-axes → central disk) so gutters, JPEG AA, and playfield background do
 * not skew the hue vote. Uses RGB + HSV + brightness gates and a circular
 * median hue check alongside the weighted hue vote.
 *
 * Game-specific hue buckets (do not generalize without recalibration):
 * B blue, R red, Y yellow, G green, P purple, O orange.
 *
 * Gates ([VisionThresholds]) are unchanged by this detector.
 */
class ColorDetector {

    data class Result(val color: TileColor, val confidence: Float)

    fun detect(cellPixels: IntArray): Result = detect(cellPixels, 0, 0)

    /**
     * @param cellWidth width of [cellPixels] row-major buffer (0 → infer square)
     * @param cellHeight height of buffer (0 → infer square)
     */
    fun detect(cellPixels: IntArray, cellWidth: Int, cellHeight: Int): Result {
        if (cellPixels.isEmpty()) return Result(TileColor.UNKNOWN, 0f)

        val (w, h) = resolveDims(cellPixels.size, cellWidth, cellHeight)
        if (w < 2 || h < 2) return Result(TileColor.UNKNOWN, 0.1f)

        val counts = FloatArray(TileColor.entries.size)
        val hsv = FloatArray(3)
        val hues = ArrayList<Float>(64)
        var saturated = 0

        val cx = (w - 1) * 0.5f
        val cy = (h - 1) * 0.5f
        val rx = (w * CENTER_RADIUS_FRAC).coerceAtLeast(1f)
        val ry = (h * CENTER_RADIUS_FRAC).coerceAtLeast(1f)

        // Dense center sampling (center disk is small); cap work on huge crops.
        val maxSamples = 2048
        val area = (Math.PI * rx * ry).toInt().coerceAtLeast(1)
        val step = (area / maxSamples).coerceAtLeast(1)

        var i = 0
        var visited = 0
        while (i < cellPixels.size) {
            val x = i % w
            val y = i / w
            val nx = (x - cx) / rx
            val ny = (y - cy) / ry
            val d2 = nx * nx + ny * ny
            if (d2 <= 1f) {
                visited++
                if (visited % step == 0 || step == 1) {
                    val p = cellPixels[i]
                    val L = PixelMath.luma(p)
                    if (L in MIN_LUMA..MAX_LUMA) {
                        PixelMath.rgbToHsv(p, hsv)
                        if (hsv[1] >= MIN_SAT && hsv[2] >= MIN_VAL) {
                            saturated++
                            val weight = (1f - d2).coerceAtLeast(0.05f)
                            val bucket = hueToColor(hsv[0])
                            counts[bucket.ordinal] += weight
                            hues.add(hsv[0])
                        }
                    }
                }
            }
            i++
        }

        if (saturated < MIN_SAMPLES) return Result(TileColor.UNKNOWN, 0.2f)

        var best = TileColor.UNKNOWN
        var bestWeight = 0f
        var totalWeight = 0f
        for (c in TileColor.entries) {
            if (c == TileColor.UNKNOWN) continue
            val wgt = counts[c.ordinal]
            totalWeight += wgt
            if (wgt > bestWeight) {
                bestWeight = wgt
                best = c
            }
        }
        if (best == TileColor.UNKNOWN || bestWeight <= 0f || totalWeight <= 0f) {
            return Result(TileColor.UNKNOWN, 0.25f)
        }

        val purity = (bestWeight / totalWeight).coerceIn(0f, 1f)
        val medianHue = circularMedianHue(hues)
        val medianColor = hueToColor(medianHue)

        // Prefer vote↔median agreement; else trust a clear center vote (purity floor).
        val color = when {
            medianColor == best -> best
            purity >= VOTE_PURITY_TRUST -> best
            else -> medianColor
        }
        if (color == TileColor.UNKNOWN) return Result(TileColor.UNKNOWN, 0.25f)

        // Clean center votes get a confidence floor so ColorShapeReconciler can
        // prefer color over false STAR/SQUARE silhouettes from gutter bleed —
        // without lowering PASS/HOLD gates.
        val conf = when {
            purity >= CLEAN_VOTE_PURITY_MIN ->
                maxOf(purity, CLEAN_VOTE_CONF_FLOOR).coerceAtMost(0.95f)
            else -> purity.coerceIn(0f, 1f)
        }
        return Result(color, conf)
    }

    private fun resolveDims(size: Int, width: Int, height: Int): Pair<Int, Int> {
        if (width > 0 && height > 0 && width * height == size) return width to height
        // solidCell / legacy callers: assume square
        val side = sqrt(size.toDouble()).toInt()
        return if (side * side == size) side to side else size to 1
    }

    /**
     * Circular median hue in degrees [0,360). Uses mean-direction unwrap then linear median.
     */
    private fun circularMedianHue(hues: List<Float>): Float {
        if (hues.isEmpty()) return 0f
        var sumSin = 0.0
        var sumCos = 0.0
        for (h in hues) {
            val rad = Math.toRadians(h.toDouble())
            sumSin += sin(rad)
            sumCos += cos(rad)
        }
        val n = hues.size.toDouble()
        var meanH = Math.toDegrees(atan2(sumSin / n, sumCos / n))
        if (meanH < 0) meanH += 360.0
        val shifted = FloatArray(hues.size) { i ->
            var d = hues[i] - meanH.toFloat()
            while (d > 180f) d -= 360f
            while (d < -180f) d += 360f
            d
        }
        shifted.sort()
        val medShift = shifted[shifted.size / 2]
        var out = meanH.toFloat() + medShift
        while (out < 0f) out += 360f
        while (out >= 360f) out -= 360f
        return out
    }

    private fun hueToColor(h: Float): TileColor = when {
        h < 15f || h >= 345f -> TileColor.R
        h < 40f -> TileColor.O
        h < 70f -> TileColor.Y
        h < 160f -> TileColor.G
        h < 255f -> TileColor.B
        h < 310f -> TileColor.P
        else -> TileColor.R
    }

    companion object {
        /** Ellipse semi-axis as fraction of full cell width/height (center disk). */
        const val CENTER_RADIUS_FRAC = 0.28f
        const val MIN_SAT = 0.25f
        const val MIN_VAL = 0.18f
        const val MIN_LUMA = 28
        const val MAX_LUMA = 242
        const val MIN_SAMPLES = 4
        /** Trust hue vote over median when weighted purity reaches this. */
        const val VOTE_PURITY_TRUST = 0.45f
        /** Floor conf for clean center votes (helps reconciler prefer color). */
        const val CLEAN_VOTE_CONF_FLOOR = 0.80f
        /** Purity required before applying [CLEAN_VOTE_CONF_FLOOR]. */
        const val CLEAN_VOTE_PURITY_MIN = 0.55f
    }
}
