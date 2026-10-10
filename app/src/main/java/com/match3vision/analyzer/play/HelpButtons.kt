package com.match3vision.analyzer.play

import com.match3vision.analyzer.vision.PixelMath

/**
 * Perk buttons on the owner's 1080×2400 frames.
 * Solo (v1) puts Shuffle then Hammer at the bottom left.
 * Multiplier Madness (v2) adds Box on that same bottom row.
 * A blue disk is charged. A grey disk is empty. Anything else is unread.
 */
object HelpButtons {
    private const val REF_W = 1080
    private const val REF_H = 2400

    const val SHUFFLE_X = 78
    const val SHUFFLE_Y = 2292
    const val HAMMER_X = 214
    const val HAMMER_Y = 2292
    const val BOX_X = 318
    const val BOX_Y = 2292

    data class Point(val x: Float, val y: Float)

    fun center(id: String, width: Int = REF_W, height: Int = REF_H): Point? {
        val raw = when (id) {
            "shuffle" -> SHUFFLE_X to SHUFFLE_Y
            "hammer" -> HAMMER_X to HAMMER_Y
            "box" -> BOX_X to BOX_Y
            else -> return null
        }
        return Point(raw.first * width / REF_W.toFloat(), raw.second * height / REF_H.toFloat())
    }

    /**
     * Geometry is the owner layout above. A null charge means that button was
     * not blue and not grey, so the caller must not tap it.
     */
    fun charges(pixels: IntArray, width: Int, height: Int): HelpPolicy.Charges {
        if (pixels.size < width * height || width < 200 || height < 400) {
            return HelpPolicy.Charges(hammer = null, shuffle = null, geometryVerified = false)
        }
        return HelpPolicy.Charges(
            hammer = chargeAt(pixels, width, height, HAMMER_X, HAMMER_Y),
            shuffle = chargeAt(pixels, width, height, SHUFFLE_X, SHUFFLE_Y),
            box = chargeAt(pixels, width, height, BOX_X, BOX_Y),
            geometryVerified = true,
        )
    }

    private fun chargeAt(pixels: IntArray, width: Int, height: Int, xRef: Int, yRef: Int): Int? {
        val cx = xRef * width / REF_W
        val cy = yRef * height / REF_H
        val rad = 16 * width / REF_W
        var blue = 0
        var grey = 0
        var n = 0
        var y = cy - rad
        while (y <= cy + rad) {
            var x = cx - rad
            while (x <= cx + rad) {
                if (x in 0 until width && y in 0 until height) {
                    val c = pixels[y * width + x]
                    val r = PixelMath.red(c)
                    val g = PixelMath.green(c)
                    val b = PixelMath.blue(c)
                    val luma = PixelMath.luma(c)
                    if (b > r + 25 && b > 90 && luma > 70) blue++
                    val spread = maxOf(r, g, b) - minOf(r, g, b)
                    if (spread < 28 && luma in 40..130) grey++
                    n++
                }
                x++
            }
            y++
        }
        if (n == 0) return null
        return when {
            blue * 2 >= n -> 1
            grey * 2 >= n -> 0
            else -> null
        }
    }
}
