package com.match3vision.analyzer.vision

/**
 * Special gems as they appear on the owner's frames.
 * Row arrow: horizontal lobes. Column arrow: vertical lobes.
 * Bomb: a red skull or a black round bomb with a fuse.
 * Color bomb: an icy white diamond or a rainbow sphere.
 * A plain gem stays [SpecialType.NONE].
 */
object OwnerSpecials {
    fun classify(pixels: IntArray, width: Int, height: Int): SpecialDetector.Result {
        if (pixels.isEmpty() || width < 8 || height < 8) return none()
        if (width.toLong() * height.toLong() > pixels.size.toLong()) return none()
        val rainbow = rainbowScore(pixels, width, height)
        if (rainbow >= 0.55f) return SpecialDetector.Result(SpecialType.LIGHTNING, rainbow)
        val icy = icyScore(pixels, width, height)
        if (icy >= 0.55f) return SpecialDetector.Result(SpecialType.LIGHTNING, icy)
        val bomb = bombScore(pixels, width, height)
        val arrow = arrowScore(pixels, width, height)
        return when {
            bomb >= 0.55f && bomb >= arrow.score ->
                SpecialDetector.Result(SpecialType.BOMB, bomb)
            arrow.score >= 0.55f ->
                SpecialDetector.Result(SpecialType.TWO_WAY_ARROW, arrow.score, arrow.axis)
            else -> none()
        }
    }

    private fun none() = SpecialDetector.Result(SpecialType.NONE, 0f)

    private data class Arrow(val score: Float, val axis: Int)

    private fun arrowScore(pixels: IntArray, w: Int, h: Int): Arrow {
        val midX = w / 2
        val midY = h / 2
        val band = (minOf(w, h) / 10).coerceAtLeast(2)
        val left = lobe(pixels, w, h, 0, midX / 2, midY - band, midY + band)
        val right = lobe(pixels, w, h, midX + midX / 2, w, midY - band, midY + band)
        val top = lobe(pixels, w, h, midX - band, midX + band, 0, midY / 2)
        val bottom = lobe(pixels, w, h, midX - band, midX + band, midY + midY / 2, h)
        val center = lobe(pixels, w, h, midX - band, midX + band, midY - band, midY + band)
        val horiz = minOf(left, right)
        val vert = minOf(top, bottom)
        val rowOk = horiz >= 0.35f && horiz > center + 0.2f
        val colOk = vert >= 0.35f && vert > center + 0.2f
        return when {
            rowOk && horiz >= vert -> Arrow(horiz.coerceIn(0f, 1f), SpecialDetector.AXIS_ROW)
            colOk -> Arrow(vert.coerceIn(0f, 1f), SpecialDetector.AXIS_COL)
            else -> Arrow(0f, 0)
        }
    }

    /** Bright yellow or cyan arrow paint, not the gem body. */
    private fun lobe(pixels: IntArray, w: Int, h: Int, x0: Int, x1: Int, y0: Int, y1: Int): Float {
        val xa = x0.coerceIn(0, w)
        val xb = x1.coerceIn(0, w)
        val ya = y0.coerceIn(0, h)
        val yb = y1.coerceIn(0, h)
        if (xa >= xb || ya >= yb) return 0f
        var hit = 0
        var n = 0
        for (y in ya until yb) {
            for (x in xa until xb) {
                val c = pixels[y * w + x]
                val r = PixelMath.red(c)
                val g = PixelMath.green(c)
                val b = PixelMath.blue(c)
                val arrow = (r > 200 && g > 170 && b < 120) || (b > 180 && g > 160 && r < 140)
                if (arrow) hit++
                n++
            }
        }
        return if (n == 0) 0f else hit.toFloat() / n
    }

    private fun bombScore(pixels: IntArray, w: Int, h: Int): Float {
        var red = 0
        var dark = 0
        var fuse = 0
        var n = 0
        val cx = w / 2f
        val cy = h / 2f
        val rad = minOf(w, h) * 0.38f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = pixels[y * w + x]
                val r = PixelMath.red(c)
                val g = PixelMath.green(c)
                val b = PixelMath.blue(c)
                val dx = x - cx
                val dy = y - cy
                val inside = dx * dx + dy * dy <= rad * rad
                if (!inside) continue
                n++
                if (r > 150 && r > g + 40 && r > b + 40) red++
                if (PixelMath.luma(c) < 45) dark++
                if (y < h / 3 && r > 180 && g > 100 && b < 80) fuse++
            }
        }
        if (n == 0) return 0f
        val redFrac = red.toFloat() / n
        val darkFrac = dark.toFloat() / n
        val skull = redFrac > 0.35f && darkFrac > 0.08f
        val round = darkFrac > 0.45f && fuse > 4
        return when {
            skull -> (redFrac * 0.7f + darkFrac).coerceIn(0f, 0.95f)
            round -> (darkFrac * 0.8f + 0.2f).coerceIn(0f, 0.95f)
            else -> 0f
        }
    }

    private fun icyScore(pixels: IntArray, w: Int, h: Int): Float {
        val cx = w / 2
        val cy = h / 2
        var bright = 0
        var n = 0
        var corner = 0
        var cornerN = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = pixels[y * w + x]
                val r = PixelMath.red(c)
                val g = PixelMath.green(c)
                val b = PixelMath.blue(c)
                val icy = r > 180 && g > 190 && b > 200
                val dx = kotlin.math.abs(x - cx)
                val dy = kotlin.math.abs(y - cy)
                val diamond = dx * h + dy * w < w * h / 3
                if (diamond) {
                    n++
                    if (icy) bright++
                }
                if (dx > w * 0.35 && dy > h * 0.35) {
                    cornerN++
                    if (icy) corner++
                }
            }
        }
        if (n == 0) return 0f
        val core = bright.toFloat() / n
        val corners = if (cornerN == 0) 0f else corner.toFloat() / cornerN
        return if (core > 0.45f && core > corners + 0.2f) core.coerceIn(0f, 0.95f) else 0f
    }

    private fun rainbowScore(pixels: IntArray, w: Int, h: Int): Float {
        val cx = w / 2f
        val cy = h / 2f
        val rad = minOf(w, h) * 0.36f
        val buckets = BooleanArray(8)
        var n = 0
        val hsv = FloatArray(3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy > rad * rad) continue
                val c = pixels[y * w + x]
                PixelMath.rgbToHsv(PixelMath.red(c), PixelMath.green(c), PixelMath.blue(c), hsv)
                if (hsv[1] < 0.45f || hsv[2] < 0.35f) continue
                n++
                val bucket = ((hsv[0] / 45f).toInt() and 7)
                buckets[bucket] = true
            }
        }
        val kinds = buckets.count { it }
        if (n < 12 || kinds < 4) return 0f
        return (kinds / 8f + 0.35f).coerceIn(0f, 0.95f)
    }
}
