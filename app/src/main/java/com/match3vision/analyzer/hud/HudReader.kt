package com.match3vision.analyzer.hud

import com.match3vision.analyzer.moves.PlayMoveRanker

/**
 * Decides whether a frame is the solo layout from the owner's recording
 * (booster card on the left, move circles to its right, no opponent red gem).
 *
 * Digits are not read. A bright ACTIVATE button is the recognized no-target
 * ready state for that solo layout. A red gem on the right is PvP: this reader
 * does not apply solo geometry there.
 */
object HudReader {
    private const val REF_W = 1080
    private const val REF_H = 2400

    const val ACTIVATE_LEFT = 40
    const val ACTIVATE_RIGHT = 300
    const val ACTIVATE_TOP = 900
    const val ACTIVATE_BOTTOM = 970

    private val soloCircles = IntArray(10) { 390 + it * 64 }

    fun read(pixels: IntArray, width: Int, height: Int): HudObservation {
        if (width < 200 || height < 400 || pixels.size < width * height) {
            return HudObservation.UNKNOWN
        }
        val turn = HudText.turn(pixels, width, height)
        val card = HudText.card(pixels, width, height)
        val pvp = opponentGem(pixels, width, height) || turn.state == HudObservation.TURN_OPPONENT
        if (pvp) {
            val mult = HudText.multiplier(pixels, width, height)
            val player = turn.state == HudObservation.TURN_YOUR || turn.state == HudObservation.TURN_TIME
            val activateWord = card.activateWord && player
            val full = card.text == "FULL" || card.text == "7/7" || activateWord
            return HudObservation(
                timer = if (turn.seconds != null) "Time Left: ${turn.seconds}" else HudObservation.NOT_DETECTABLE,
                moves = HudObservation.NOT_DETECTABLE,
                mode = "pvp",
                boosterFill = card.text,
                boosterPhase = when {
                    activateWord || full -> BoosterMonitor.READY
                    card.text.endsWith("/7") -> BoosterMonitor.CHARGING
                    else -> BoosterMonitor.UNKNOWN
                },
                blueFactor = if (full) PlayMoveRanker.FULL_BAR_BLUE_FACTOR else 1.0,
                soloLayout = false,
                activate = if (activateWord) "yes" else "no",
                boosterTarget = if (activateWord) "none" else "unknown",
                yourTurn = turn.label,
                turnState = turn.state,
                timeLeftSeconds = turn.seconds,
                multiplier = mult.value,
                multiplierNote = mult.note,
            )
        }
        val activate = activateBright(pixels, width, height) || card.activateWord
        val circles = circleReport(pixels, width, height)
        // A flat field is classifiable at every sample and is still not a circle row.
        val solo = activate || (circles != null && circleRowStructured(pixels, width, height))
        if (!solo) return HudObservation.UNKNOWN
        val legend = HudText.legend(pixels, width, height)
        val fill = when {
            card.text == "ACTIVATE" || card.text == "FULL" || card.text == "7/7" || activate -> "full"
            card.text.endsWith("/7") -> card.text
            else -> "unknown"
        }
        return HudObservation(
            timer = if (turn.seconds != null) "Time Left: ${turn.seconds}" else HudObservation.NOT_DETECTABLE,
            moves = circles ?: HudObservation.NOT_DETECTABLE,
            rounds = HudObservation.NOT_DETECTABLE,
            mode = "solo",
            boosterFill = fill,
            boosterPhase = when {
                activate || fill == "full" -> BoosterMonitor.READY
                fill.endsWith("/7") -> BoosterMonitor.CHARGING
                else -> BoosterMonitor.phase(fill)
            },
            blueFactor = if (activate || fill == "full") PlayMoveRanker.FULL_BAR_BLUE_FACTOR else 1.0,
            soloLayout = true,
            activate = if (activate) "yes" else "no",
            boosterTarget = if (activate) "none" else "unknown",
            legend = legend.first,
            yourTurn = turn.label,
            turnState = turn.state,
            timeLeftSeconds = turn.seconds,
            legendPoints = legend.second,
        )
    }

    fun activateCenter(width: Int, height: Int): Pair<Float, Float> {
        val x = (ACTIVATE_LEFT + ACTIVATE_RIGHT) / 2.0 * width / REF_W
        val y = (ACTIVATE_TOP + ACTIVATE_BOTTOM) / 2.0 * height / REF_H
        return x.toFloat() to y.toFloat()
    }

    private fun opponentGem(pixels: IntArray, width: Int, height: Int): Boolean {
        var reds = 0
        for (y in 875..947) {
            val py = scaleY(y, height)
            for (x in 994..1063 step 2) {
                val color = pixel(pixels, width, height, scaleX(x, width), py) ?: return false
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff
                if (r > 110 && r > g + 40 && r > b + 30) reds++
            }
        }
        return reds >= 12
    }

    private fun activateBright(pixels: IntArray, width: Int, height: Int): Boolean {
        var sum = 0
        var bright = 0
        var n = 0
        for (y in ACTIVATE_TOP..ACTIVATE_BOTTOM step 4) {
            val py = scaleY(y, height)
            for (x in ACTIVATE_LEFT..ACTIVATE_RIGHT step 4) {
                val color = pixel(pixels, width, height, scaleX(x, width), py) ?: return false
                val luma = luma(color)
                sum += luma
                if (luma > 130) bright++
                n++
            }
        }
        if (n == 0) return false
        return sum / n > 150 && bright * 2 >= n
    }

    /** Null when the ten samples are not clearly empty or filled circles. */
    private fun circleReport(pixels: IntArray, width: Int, height: Int): String? {
        var bright = 0
        var dark = 0
        for (cx in soloCircles) {
            val mean = meanLuma(pixels, width, height, scaleX(cx, width), scaleY(930, height))
                ?: return null
            when {
                mean > 90 -> bright++
                mean < 45 -> dark++
                else -> return null
            }
        }
        if (bright + dark < soloCircles.size) return null
        return "bright=$bright/${soloCircles.size} dark=$dark/${soloCircles.size}"
    }

    /** True when circle centers differ from the gaps between them. */
    private fun circleRowStructured(pixels: IntArray, width: Int, height: Int): Boolean {
        var contrast = 0
        val cy = scaleY(930, height)
        for (i in 0 until soloCircles.size - 1) {
            val center = meanLuma(pixels, width, height, scaleX(soloCircles[i], width), cy) ?: return false
            val gapX = (soloCircles[i] + soloCircles[i + 1]) / 2
            val gap = meanLuma(pixels, width, height, scaleX(gapX, width), cy) ?: return false
            if (kotlin.math.abs(center - gap) >= 40) contrast++
        }
        return contrast >= 4
    }

    private fun meanLuma(pixels: IntArray, width: Int, height: Int, cx: Int, cy: Int): Int? {
        var sum = 0
        var n = 0
        for (y in (cy - 3)..(cy + 3)) {
            for (x in (cx - 3)..(cx + 3)) {
                val color = pixel(pixels, width, height, x, y) ?: return null
                sum += luma(color)
                n++
            }
        }
        return if (n == 0) null else sum / n
    }

    private fun scaleX(x: Int, width: Int) = x * width / REF_W
    private fun scaleY(y: Int, height: Int) = y * height / REF_H

    private fun pixel(pixels: IntArray, width: Int, height: Int, x: Int, y: Int): Int? {
        if (x !in 0 until width || y !in 0 until height) return null
        return pixels[y * width + x]
    }

    private fun luma(color: Int): Int {
        val r = (color shr 16) and 0xff
        val g = (color shr 8) and 0xff
        val b = color and 0xff
        return (r + g + b) / 3
    }

    fun argb(r: Int, g: Int, b: Int): Int = (0xff shl 24) or (r shl 16) or (g shl 8) or b
}

/** Tap point for a recognized solo ACTIVATE button. Null means do not tap. */
object SoloBooster {
    data class Tap(val x: Float, val y: Float)

    fun plan(hud: HudObservation, width: Int, height: Int, controlEnabled: Boolean): Tap? {
        if (!BoosterMonitor.mayTap(hud.soloLayout, hud.activate == "yes", controlEnabled)) return null
        val (x, y) = HudReader.activateCenter(width, height)
        if (x < 0f || y < 0f) return null
        return Tap(x, y)
    }
}
