package com.match3vision.analyzer.vision

/**
 * Columns covered by the analyzer's own floating panel.
 *
 * MediaProjection records the composed screen, so a bright, low-saturation
 * panel (the bubble buttons) is in the frame. Those columns are not board
 * chrome to trim away: the gems continue underneath. Callers skip them when
 * measuring gutters and still keep them inside the board ROI.
 *
 * A clear PvP frame has no such run. An empty mask must leave projection
 * unchanged.
 */
class OverlayColumnMask(
    val covered: BooleanArray,
) {
    fun covers(x: Int): Boolean = x >= 0 && x < covered.size && covered[x]

    val count: Int
        get() {
            var n = 0
            for (c in covered) if (c) n++
            return n
        }

    fun isEmpty(): Boolean = count == 0

    /** Inclusive-exclusive runs, for diagnostics and tests. */
    fun runs(): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < covered.size) {
            if (!covered[i]) {
                i++
                continue
            }
            val start = i
            while (i < covered.size && covered[i]) i++
            out.add(start to i)
        }
        return out
    }

    fun describe(): String {
        val runs = runs()
        if (runs.isEmpty()) return "none"
        return runs.joinToString(",") { (a, b) -> "$a..${b - 1}" }
    }

    companion object {
        /** Mean luma above this and saturation below [MAX_SAT] → panel-like. */
        const val MIN_LUMA = 90f
        const val MAX_SAT = 0.30f

        /** Ignore specks. The device bubble is hundreds of pixels wide. */
        const val MIN_RUN = 80

        fun empty(width: Int) = OverlayColumnMask(BooleanArray(width.coerceAtLeast(0)))

        fun detect(pixels: IntArray, width: Int, height: Int): OverlayColumnMask {
            if (width <= 0 || height <= 0 || pixels.size < width * height) {
                return empty(width.coerceAtLeast(0))
            }
            val y0 = (height * 0.20f).toInt().coerceIn(0, height - 1)
            val y1 = (height * 0.75f).toInt().coerceIn(y0 + 1, height)
            val yStep = ((y1 - y0) / 48).coerceAtLeast(4)
            val flagged = BooleanArray(width)
            for (x in 0 until width) {
                var lumaSum = 0.0
                var satSum = 0.0
                var n = 0
                var y = y0
                while (y < y1) {
                    val p = pixels[y * width + x]
                    val r = PixelMath.red(p)
                    val g = PixelMath.green(p)
                    val b = PixelMath.blue(p)
                    lumaSum += PixelMath.luma(p)
                    val max = maxOf(r, g, b)
                    val min = minOf(r, g, b)
                    satSum += if (max == 0) 0.0 else (max - min).toDouble() / max
                    n++
                    y += yStep
                }
                if (n > 0 && lumaSum / n > MIN_LUMA && satSum / n < MAX_SAT) {
                    flagged[x] = true
                }
            }
            val covered = BooleanArray(width)
            var i = 0
            while (i < width) {
                if (!flagged[i]) {
                    i++
                    continue
                }
                val start = i
                while (i < width && flagged[i]) i++
                if (i - start >= MIN_RUN) {
                    for (x in start until i) covered[x] = true
                }
            }
            return OverlayColumnMask(covered)
        }
    }
}
