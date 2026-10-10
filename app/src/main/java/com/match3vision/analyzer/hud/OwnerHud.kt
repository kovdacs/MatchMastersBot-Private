package com.match3vision.analyzer.hud

import com.match3vision.analyzer.vision.PixelMath

/**
 * Color cues from the owner's 1080×2400 frames.
 * Glyphs still win when they match. These bars and banners cover the frames
 * whose letters are not the glyph font: a blue Your Turn bar, a red Opponent's
 * Turn bar, the center banners, and the end screens.
 */
internal object OwnerHud {
    private const val REF_W = 1080
    private const val REF_H = 2400

    /** Left multiplier box, the "xN" above our score. */
    const val MULT_LEFT = 270
    const val MULT_TOP = 420
    const val MULT_RIGHT = 530
    const val MULT_BOTTOM = 520

    /** Yellow "Pick a piece" sits above the score, under the character. */
    const val PROMPT_LEFT = 250
    const val PROMPT_TOP = 680
    const val PROMPT_RIGHT = 830
    const val PROMPT_BOTTOM = 820

    const val END_OUT = "out-of-moves"
    const val END_WINS = "opponent-wins"
    const val BANNER_OPPONENT = "opponent-banner"
    const val BANNER_YOUR = "your-banner"

    fun cue(pixels: IntArray, width: Int, height: Int): String? {
        val yellow = fraction(pixels, width, height, 120, 960, 1280, 1680, ::yellow)
        val pink = fraction(pixels, width, height, 160, 920, 1040, 1400, ::pink)
        val red = fraction(pixels, width, height, 140, 940, 1200, 1720, ::redInk)
        val blue = fraction(pixels, width, height, 140, 940, 1200, 1720, ::blueInk)
        return when {
            yellow > 0.08 -> END_OUT
            pink > 0.06 -> END_WINS
            blue > 0.05 && blue >= red -> BANNER_YOUR
            red > 0.06 -> BANNER_OPPONENT
            else -> null
        }
    }

    fun apply(glyphs: HudText.Turn, pixels: IntArray, width: Int, height: Int): HudText.Turn {
        val cue = cue(pixels, width, height)
        var turn = glyphs
        if (turn.state == HudObservation.NOT_DETECTABLE) {
            when (turnBar(pixels, width, height)) {
                HudObservation.TURN_OPPONENT ->
                    turn = HudText.Turn(HudObservation.TURN_OPPONENT, "Opponent's Turn", null)
                HudObservation.TURN_YOUR ->
                    turn = HudText.Turn(HudObservation.TURN_YOUR, "Your Turn", null)
            }
        }
        return when (cue) {
            BANNER_OPPONENT -> turn.copy(
                state = HudObservation.TURN_OPPONENT,
                label = "Opponent's Turn",
            )
            BANNER_YOUR -> if (turn.state == HudObservation.TURN_OPPONENT) {
                turn
            } else {
                turn.copy(state = HudObservation.TURN_YOUR, label = "Your Turn")
            }
            else -> turn
        }
    }

    fun endScreen(pixels: IntArray, width: Int, height: Int): String? = when (cue(pixels, width, height)) {
        END_OUT, END_WINS -> cue(pixels, width, height)
        else -> null
    }

    fun ourMultiplier(pixels: IntArray, width: Int, height: Int): Int? =
        HudText.boxedDigits(pixels, width, height, MULT_LEFT, MULT_TOP, MULT_RIGHT, MULT_BOTTOM)

    fun pickAPiece(pixels: IntArray, width: Int, height: Int): Boolean =
        fraction(pixels, width, height, PROMPT_LEFT, PROMPT_RIGHT, PROMPT_TOP, PROMPT_BOTTOM, ::yellow) > 0.015

    /** Blue bar is Your Turn. Red bar is Opponent's Turn. A thin read is ignored. */
    fun turnBar(pixels: IntArray, width: Int, height: Int): String? {
        val blue = fraction(pixels, width, height, HudText.TURN_LEFT, HudText.TURN_RIGHT, HudText.TURN_TOP, HudText.TURN_BOTTOM, ::blueBar)
        val red = fraction(pixels, width, height, HudText.TURN_LEFT, HudText.TURN_RIGHT, HudText.TURN_TOP, HudText.TURN_BOTTOM, ::redBar)
        return when {
            red > 0.28 && red > blue * 1.3 -> HudObservation.TURN_OPPONENT
            blue > 0.35 && blue > red * 1.4 -> HudObservation.TURN_YOUR
            else -> null
        }
    }

    private fun fraction(
        pixels: IntArray,
        width: Int,
        height: Int,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
        match: (Int) -> Boolean,
    ): Double {
        val x0 = left * width / REF_W
        val x1 = right * width / REF_W
        val y0 = top * height / REF_H
        val y1 = bottom * height / REF_H
        if (x1 <= x0 || y1 <= y0) return 0.0
        var hit = 0
        var n = 0
        val step = 2
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val i = y * width + x
                if (i in pixels.indices && match(pixels[i])) hit++
                n++
                x += step
            }
            y += step
        }
        return if (n == 0) 0.0 else hit.toDouble() / n
    }

    private fun yellow(c: Int): Boolean {
        val r = PixelMath.red(c)
        val g = PixelMath.green(c)
        val b = PixelMath.blue(c)
        return r > 190 && g > 150 && b < 90
    }

    private fun pink(c: Int): Boolean {
        val r = PixelMath.red(c)
        val g = PixelMath.green(c)
        val b = PixelMath.blue(c)
        return r > 190 && b > 90 && g < 130 && r > g + 40
    }

    private fun redInk(c: Int): Boolean {
        val r = PixelMath.red(c)
        val g = PixelMath.green(c)
        val b = PixelMath.blue(c)
        return r > 170 && g < 90 && b < 100
    }

    private fun blueInk(c: Int): Boolean {
        val r = PixelMath.red(c)
        val g = PixelMath.green(c)
        val b = PixelMath.blue(c)
        return b > 170 && r < 120 && g > 70
    }

    private fun blueBar(c: Int): Boolean {
        val r = PixelMath.red(c)
        val g = PixelMath.green(c)
        val b = PixelMath.blue(c)
        return b > 140 && b > r + 30 && g > 60
    }

    private fun redBar(c: Int): Boolean {
        val r = PixelMath.red(c)
        val g = PixelMath.green(c)
        val b = PixelMath.blue(c)
        return r > 120 && r > b + 25 && g < 110
    }
}
