package com.match3vision.analyzer.vision

/**
 * HSV heuristics → [TileColor] + confidence on a cell crop.
 *
 * Game-specific hue buckets (do not generalize without recalibration):
 * B blue, R red, Y yellow, G green, P purple, O orange.
 */
class ColorDetector {

    data class Result(val color: TileColor, val confidence: Float)

    fun detect(cellPixels: IntArray): Result {
        if (cellPixels.isEmpty()) return Result(TileColor.UNKNOWN, 0f)

        val counts = IntArray(TileColor.entries.size)
        val hsv = FloatArray(3)
        var saturated = 0
        val step = (cellPixels.size / 256).coerceAtLeast(1)

        var i = 0
        while (i < cellPixels.size) {
            PixelMath.rgbToHsv(cellPixels[i], hsv)
            // Ignore near-gray / dark pixels for hue voting
            if (hsv[1] >= 0.25f && hsv[2] >= 0.18f) {
                saturated++
                val bucket = hueToColor(hsv[0])
                counts[bucket.ordinal]++
            }
            i += step
        }

        if (saturated < 4) return Result(TileColor.UNKNOWN, 0.2f)

        var best = TileColor.UNKNOWN
        var bestCount = 0
        for (c in TileColor.entries) {
            if (c == TileColor.UNKNOWN) continue
            if (counts[c.ordinal] > bestCount) {
                bestCount = counts[c.ordinal]
                best = c
            }
        }
        if (best == TileColor.UNKNOWN || bestCount == 0) {
            return Result(TileColor.UNKNOWN, 0.25f)
        }
        val conf = (bestCount.toFloat() / saturated).coerceIn(0f, 1f)
        return Result(best, conf)
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
}
