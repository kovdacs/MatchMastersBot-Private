package com.match3vision.analyzer.input

import com.match3vision.analyzer.hud.HudObservation

/**
 * What the screen is, before a swipe is allowed.
 * A dimmed transition is not a menu and is not an unknown HUD.
 * Solo (circles or Your Turn) is never unknown.
 */
object PlayGate {
    const val OURS = "ours"
    const val OPPONENT = "opponent"
    const val MENU = "menu"
    const val UNKNOWN = "unknown"
    const val DIMMED = "dimmed"

    /** Mean luma under this is a level-end or shuffle fade, not a decision. */
    const val DIM_MEAN = 35

    fun kind(hud: HudObservation, visionPass: Boolean, dimmed: Boolean): String {
        if (dimmed) return DIMMED
        if (hud.turnState == HudObservation.TURN_OPPONENT) return OPPONENT
        val circles = hud.circlesClassifiable && (hud.circlesBright ?: 0) > 0
        val solo = hud.soloPositive || circles || hud.turnState == HudObservation.TURN_YOUR
        val pvpOurs = hud.mode == "pvp" && hud.playerTurn()
        if (solo || pvpOurs) return OURS
        if (!visionPass) return MENU
        return UNKNOWN
    }

    fun dimmed(pixels: IntArray?, width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        if (pixels == null || width <= 0 || height <= 0 || pixels.size < width * height) return false
        val l = left.coerceIn(0, width - 1)
        val t = top.coerceIn(0, height - 1)
        val r = if (right > l) right.coerceAtMost(width) else width
        val b = if (bottom > t) bottom.coerceAtMost(height) else height
        var sum = 0L
        var n = 0
        var y = t
        while (y < b) {
            val row = y * width
            var x = l
            while (x < r) {
                val color = pixels[row + x]
                val rr = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val bb = color and 0xff
                sum += (rr + g + bb) / 3
                n++
                x += 8
            }
            y += 8
        }
        if (n == 0) return false
        return sum / n < DIM_MEAN
    }
}
