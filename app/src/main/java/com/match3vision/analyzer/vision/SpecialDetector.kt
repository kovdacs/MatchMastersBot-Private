package com.match3vision.analyzer.vision

/**
 * Conservative special-overlay detector (two-way arrow, lightning, bomb).
 * If confidence &lt; [VisionThresholds.SPECIAL_MIN_CONFIDENCE] → [SpecialType.NONE]
 * (prefer NONE over false specials).
 */
class SpecialDetector {

    data class Result(val special: SpecialType, val confidence: Float)

    fun detect(cellPixels: IntArray, cellWidth: Int, cellHeight: Int): Result {
        if (cellPixels.isEmpty() || cellWidth < 4 || cellHeight < 4) {
            return none()
        }

        val lumaMean = PixelMath.meanLuma(cellPixels)
        var bright = 0
        var darkCore = 0
        var samples = 0
        val step = (cellPixels.size / 128).coerceAtLeast(1)
        var i = 0
        while (i < cellPixels.size) {
            val L = PixelMath.luma(cellPixels[i])
            if (L >= 220) bright++
            if (L <= 40) darkCore++
            samples++
            i += step
        }
        if (samples == 0) return none()

        val brightFrac = bright.toFloat() / samples
        val darkFrac = darkCore.toFloat() / samples

        // Bomb heuristic: dark core + surrounding mid tones
        var bombConf = 0f
        if (darkFrac in 0.12f..0.45f && lumaMean in 40f..140f && brightFrac < 0.15f) {
            bombConf = (darkFrac * 1.5f + 0.25f).coerceIn(0f, 0.95f)
        }

        // Lightning: elongated bright streak — high bright fraction in a band
        val streak = streakScore(cellPixels, cellWidth, cellHeight)
        var lightningConf = 0f
        if (streak > 0.55f && brightFrac > 0.08f) {
            lightningConf = (streak * 0.85f).coerceIn(0f, 0.95f)
        }

        // Two-way arrow: two opposing bright lobes (left-right or top-bottom)
        val arrow = arrowScore(cellPixels, cellWidth, cellHeight)
        var arrowConf = 0f
        if (arrow > 0.55f) {
            arrowConf = (arrow * 0.80f).coerceIn(0f, 0.95f)
        }

        val best = listOf(
            SpecialType.BOMB to bombConf,
            SpecialType.LIGHTNING to lightningConf,
            SpecialType.TWO_WAY_ARROW to arrowConf,
        ).maxByOrNull { it.second } ?: (SpecialType.NONE to 0f)

        if (best.second < VisionThresholds.SPECIAL_MIN_CONFIDENCE) {
            return none()
        }
        return Result(best.first, best.second)
    }

    private fun none() = Result(SpecialType.NONE, 0f)

    private fun streakScore(pixels: IntArray, w: Int, h: Int): Float {
        // Compare max row bright-ratio vs max col bright-ratio — elongated wins
        var maxRow = 0f
        for (y in 0 until h) {
            var b = 0
            for (x in 0 until w) if (PixelMath.luma(pixels[y * w + x]) >= 200) b++
            maxRow = maxOf(maxRow, b.toFloat() / w)
        }
        var maxCol = 0f
        for (x in 0 until w) {
            var b = 0
            for (y in 0 until h) if (PixelMath.luma(pixels[y * w + x]) >= 200) b++
            maxCol = maxOf(maxCol, b.toFloat() / h)
        }
        val dominant = maxOf(maxRow, maxCol)
        val other = minOf(maxRow, maxCol)
        return if (dominant > 0.25f && dominant > other * 1.6f) dominant else 0f
    }

    private fun arrowScore(pixels: IntArray, w: Int, h: Int): Float {
        val midY = h / 2
        val midX = w / 2
        val band = (h / 5).coerceAtLeast(1)
        fun regionBright(x0: Int, x1: Int, y0: Int, y1: Int): Float {
            var b = 0
            var n = 0
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    if (PixelMath.luma(pixels[y * w + x]) >= 190) b++
                    n++
                }
            }
            return if (n == 0) 0f else b.toFloat() / n
        }
        val left = regionBright(0, midX, midY - band, midY + band)
        val right = regionBright(midX, w, midY - band, midY + band)
        val top = regionBright(midX - band, midX + band, 0, midY)
        val bottom = regionBright(midX - band, midX + band, midY, h)
        val horiz = minOf(left, right) * 2f
        val vert = minOf(top, bottom) * 2f
        return maxOf(horiz, vert).coerceIn(0f, 1f)
    }
}
