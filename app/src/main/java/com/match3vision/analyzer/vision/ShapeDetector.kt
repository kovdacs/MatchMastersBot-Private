package com.match3vision.analyzer.vision

import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Contour-ish shape heuristics on a cell crop using JVM-friendly pixel ops
 * (binary mask + moments / circularity / aspect — no OpenCV).
 *
 * Near-full rectangular color blobs (typical solid tile fill without a carved
 * silhouette) return [TileShape.UNKNOWN] with low confidence so
 * [ColorShapeReconciler] can trust color → expected shape.
 */
class ShapeDetector(
    private val maskLumaThreshold: Int = 40,
    private val minSat: Float = 0.20f,
) {

    data class Result(val shape: TileShape, val confidence: Float)

    fun detect(cellPixels: IntArray, cellWidth: Int, cellHeight: Int): Result {
        if (cellPixels.isEmpty() || cellWidth < 3 || cellHeight < 3) {
            return Result(TileShape.UNKNOWN, 0f)
        }

        val mask = BooleanArray(cellPixels.size)
        val hsv = FloatArray(3)
        var foreground = 0
        for (i in cellPixels.indices) {
            PixelMath.rgbToHsv(cellPixels[i], hsv)
            val on = PixelMath.luma(cellPixels[i]) >= maskLumaThreshold && hsv[1] >= minSat
            mask[i] = on
            if (on) foreground++
        }
        if (foreground < 8) return Result(TileShape.UNKNOWN, 0.15f)

        val cellFill = foreground.toFloat() / (cellWidth * cellHeight)

        var minX = cellWidth
        var minY = cellHeight
        var maxX = -1
        var maxY = -1
        var sumX = 0.0
        var sumY = 0.0
        for (y in 0 until cellHeight) {
            for (x in 0 until cellWidth) {
                if (!mask[y * cellWidth + x]) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                sumX += x
                sumY += y
            }
        }
        if (maxX < minX) return Result(TileShape.UNKNOWN, 0.1f)

        val bw = (maxX - minX + 1).toFloat()
        val bh = (maxY - minY + 1).toFloat()
        val aspect = bw / bh.coerceAtLeast(1f)
        val bboxFill = foreground.toFloat() / (bw * bh).coerceAtLeast(1f)

        val perimeter = estimatePerimeter(mask, cellWidth, cellHeight)
        val circularity = if (perimeter > 1f) {
            ((4.0 * PI * foreground) / (perimeter * perimeter)).toFloat()
        } else 0f

        val cx = sumX / foreground
        val cy = sumY / foreground
        var sumR = 0.0
        var sumR2 = 0.0
        for (y in 0 until cellHeight) {
            for (x in 0 until cellWidth) {
                if (!mask[y * cellWidth + x]) continue
                val dx = x - cx
                val dy = y - cy
                val r = sqrt(dx * dx + dy * dy)
                sumR += r
                sumR2 += r * r
            }
        }
        val meanR = sumR / foreground
        val varR = (sumR2 / foreground - meanR * meanR).coerceAtLeast(0.0)
        val relRadialVar = if (meanR > 1e-3) (varR / (meanR * meanR)).toFloat() else 1f

        // Solid rectangular blob filling most of the cell → no silhouette.
        // Filled squares have circularity ≈ π/4 (~0.79); BoardFinder crops often include
        // dark gutters and can be non-square, so do not gate on aspect. Real carved
        // circles leave empty corners (cellFill typically ≪ 0.70) and stay classifiable.
        if (cellFill >= 0.70f && bboxFill >= 0.85f) {
            return Result(TileShape.UNKNOWN, 0.35f)
        }

        return classify(bboxFill, circularity, aspect, relRadialVar, cellFill)
    }

    private fun classify(
        fill: Float,
        circularity: Float,
        aspect: Float,
        relRadialVar: Float,
        cellFill: Float,
    ): Result {
        if (circularity >= 0.65f && fill in 0.45f..0.95f && relRadialVar < 0.08f &&
            cellFill < 0.70f
        ) {
            return Result(TileShape.CIRCLE, circularity.coerceIn(0.55f, 0.98f))
        }
        if (circularity in 0.45f..0.75f && fill in 0.55f..0.92f &&
            aspect in 0.85f..1.15f && relRadialVar < 0.12f && cellFill < 0.80f
        ) {
            return Result(TileShape.HEX, 0.70f)
        }
        if (fill >= 0.72f && aspect in 0.85f..1.15f && circularity < 0.70f && cellFill < 0.80f) {
            return Result(TileShape.SQUARE, fill.coerceIn(0.55f, 0.95f))
        }
        if (fill in 0.35f..0.70f && aspect in 0.75f..1.30f && circularity in 0.25f..0.60f) {
            return Result(TileShape.DIAMOND, 0.68f)
        }
        if (fill in 0.28f..0.58f && circularity < 0.50f) {
            return Result(TileShape.TRIANGLE, 0.62f)
        }
        if (relRadialVar >= 0.10f && fill in 0.25f..0.70f) {
            return Result(TileShape.STAR, (0.55f + relRadialVar).coerceIn(0.55f, 0.92f))
        }
        return when {
            circularity >= 0.55f && cellFill < 0.70f -> Result(TileShape.CIRCLE, circularity * 0.8f)
            relRadialVar >= 0.08f -> Result(TileShape.STAR, 0.50f)
            fill >= 0.70f && cellFill < 0.80f -> Result(TileShape.SQUARE, fill * 0.75f)
            else -> Result(TileShape.UNKNOWN, 0.30f)
        }
    }

    private fun estimatePerimeter(mask: BooleanArray, w: Int, h: Int): Float {
        var edge = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!mask[y * w + x]) continue
                if (x == 0 || !mask[y * w + x - 1]) edge++
                if (x == w - 1 || !mask[y * w + x + 1]) edge++
                if (y == 0 || !mask[(y - 1) * w + x]) edge++
                if (y == h - 1 || !mask[(y + 1) * w + x]) edge++
            }
        }
        return edge / 2f
    }
}
