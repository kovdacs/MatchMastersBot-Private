package com.match3vision.analyzer.hud

import com.match3vision.analyzer.moves.PlayMoveRanker

/**
 * Reads the HUD. Ten classifiable bright move circles are the solo signal and
 * override a high opponent-red count. A positive Opponent's Turn with no circle
 * row stays PvP. Your Turn or Time Left plus that red gem, and no circle row,
 * stays PvP. Anything else is treated as solo so lookahead and the booster can
 * run; the session still stops only on a positively read Opponent's Turn.
 *
 * A flat dark field is classifiable and is not a circle row, so moves remaining
 * stays unread. The ACTIVATE tap requires the left-card word, not a bright rect.
 */
object HudReader {
    private const val REF_W = 1080
    private const val REF_H = 2400

    const val ACTIVATE_LEFT = 40
    const val ACTIVATE_RIGHT = 300
    const val ACTIVATE_TOP = 900
    const val ACTIVATE_BOTTOM = 970

    private val soloCircles = IntArray(10) { 390 + it * 64 }
    private const val OPPONENT_RED_MIN = 12

    fun read(pixels: IntArray, width: Int, height: Int): HudObservation {
        if (width < 200 || height < 400 || pixels.size < width * height) {
            return HudObservation.UNKNOWN
        }
        val turn = HudText.turn(pixels, width, height)
        val card = HudText.card(pixels, width, height)
        val reds = opponentReds(pixels, width, height)
        val activateSample = activateSample(pixels, width, height)
        val circlesSample = circleSample(pixels, width, height)
        val scores = formatScores(reds, turn, activateSample, circlesSample)
        val structured = circleRowStructured(pixels, width, height)
        // A flat field is classifiable at every sample and is still not a circle row.
        val circleSignal = circlesSample.classifiable && (circlesSample.bright > 0 || structured)
        val movesRemaining = if (circleSignal) circlesSample.bright else null
        val word = card.activateWord
        val player = turn.state == HudObservation.TURN_YOUR || turn.state == HudObservation.TURN_TIME
        val opponent = turn.state == HudObservation.TURN_OPPONENT
        val redPvp = reds >= OPPONENT_RED_MIN && player
        if ((opponent || redPvp) && !circleSignal) {
            val mult = HudText.multiplier(pixels, width, height)
            val ready = word && player
            val full = card.text == "FULL" || card.text == "7/7" || ready
            return withCircles(
                HudObservation(
                    timer = if (turn.seconds != null) "Time Left: ${turn.seconds}" else HudObservation.NOT_DETECTABLE,
                    moves = circlesSample.report ?: HudObservation.NOT_DETECTABLE,
                    mode = "pvp",
                    boosterFill = card.text,
                    boosterPhase = when {
                        ready || full -> BoosterMonitor.READY
                        card.text.endsWith("/7") -> BoosterMonitor.CHARGING
                        else -> BoosterMonitor.UNKNOWN
                    },
                    blueFactor = if (full) PlayMoveRanker.FULL_BAR_BLUE_FACTOR else 1.0,
                    soloLayout = false,
                    activate = if (ready) "yes" else "no",
                    boosterTarget = if (ready) "none" else "unknown",
                    yourTurn = turn.label,
                    turnState = turn.state,
                    timeLeftSeconds = turn.seconds,
                    multiplier = mult.value,
                    multiplierNote = mult.note,
                    hudState = HudObservation.hudStateFor(turn.state, soloLayout = false),
                    hudScores = scores,
                ),
                movesRemaining,
                circlesSample,
                word,
            )
        }
        val knownSolo = circleSignal || ((activateSample.bright || word) && reds < OPPONENT_RED_MIN)
        if (!knownSolo) {
            return withCircles(
                HudObservation(
                    timer = if (turn.seconds != null) "Time Left: ${turn.seconds}" else HudObservation.NOT_DETECTABLE,
                    moves = HudObservation.NOT_DETECTABLE,
                    mode = "solo",
                    soloLayout = true,
                    yourTurn = turn.label,
                    turnState = turn.state,
                    timeLeftSeconds = turn.seconds,
                    hudState = HudObservation.HUD_UNKNOWN,
                    hudScores = scores,
                ),
                movesRemaining = null,
                circlesSample,
                word,
            )
        }
        val activate = activateSample.bright || word
        val legend = HudText.legend(pixels, width, height)
        val fill = when {
            card.text == "ACTIVATE" || word -> "ACTIVATE"
            card.text == "FULL" || card.text == "7/7" || activate -> "full"
            card.text.endsWith("/7") -> card.text
            else -> "unknown"
        }
        return withCircles(
            HudObservation(
                timer = if (turn.seconds != null) "Time Left: ${turn.seconds}" else HudObservation.NOT_DETECTABLE,
                moves = circlesSample.report ?: HudObservation.NOT_DETECTABLE,
                rounds = HudObservation.NOT_DETECTABLE,
                mode = "solo",
                boosterFill = fill,
                boosterPhase = when {
                    activate || fill == "full" || fill == "ACTIVATE" -> BoosterMonitor.READY
                    fill.endsWith("/7") -> BoosterMonitor.CHARGING
                    else -> BoosterMonitor.phase(fill)
                },
                blueFactor = if (activate || fill == "full") PlayMoveRanker.FULL_BAR_BLUE_FACTOR else 1.0,
                soloLayout = true,
                activate = if (activate) "yes" else "no",
                boosterTarget = if (word) "none" else if (activate) "none" else "unknown",
                legend = legend.first,
                yourTurn = turn.label,
                turnState = turn.state,
                timeLeftSeconds = turn.seconds,
                legendPoints = legend.second,
                hudState = HudObservation.hudStateFor(turn.state, soloLayout = true),
                hudScores = scores,
            ),
            movesRemaining,
            circlesSample,
            word,
        )
    }

    private fun withCircles(
        hud: HudObservation,
        movesRemaining: Int?,
        circles: CircleSample,
        activateWord: Boolean,
    ): HudObservation = hud.copy(
        movesRemaining = movesRemaining,
        circlesBright = if (circles.classifiable) circles.bright else null,
        circlesClassifiable = circles.classifiable,
        activateWord = activateWord,
    )

    fun activateCenter(width: Int, height: Int): Pair<Float, Float> {
        val x = (ACTIVATE_LEFT + ACTIVATE_RIGHT) / 2.0 * width / REF_W
        val y = (ACTIVATE_TOP + ACTIVATE_BOTTOM) / 2.0 * height / REF_H
        return x.toFloat() to y.toFloat()
    }

    private fun opponentReds(pixels: IntArray, width: Int, height: Int): Int {
        var reds = 0
        for (y in 875..947) {
            val py = scaleY(y, height)
            for (x in 994..1063 step 2) {
                val color = pixel(pixels, width, height, scaleX(x, width), py) ?: return reds
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff
                if (r > 110 && r > g + 40 && r > b + 30) reds++
            }
        }
        return reds
    }

    private data class ActivateSample(val bright: Boolean, val mean: Int, val brightFraction: Double)

    private fun activateSample(pixels: IntArray, width: Int, height: Int): ActivateSample {
        var sum = 0
        var bright = 0
        var n = 0
        for (y in ACTIVATE_TOP..ACTIVATE_BOTTOM step 4) {
            val py = scaleY(y, height)
            for (x in ACTIVATE_LEFT..ACTIVATE_RIGHT step 4) {
                val color = pixel(pixels, width, height, scaleX(x, width), py) ?: return ActivateSample(false, 0, 0.0)
                val luma = luma(color)
                sum += luma
                if (luma > 130) bright++
                n++
            }
        }
        if (n == 0) return ActivateSample(false, 0, 0.0)
        val mean = sum / n
        val fraction = bright.toDouble() / n
        return ActivateSample(mean > 150 && bright * 2 >= n, mean, fraction)
    }

    private data class CircleSample(val bright: Int, val dark: Int, val classifiable: Boolean, val report: String?)

    /** Report is set only when every sample is a clear empty or filled circle. */
    private fun circleSample(pixels: IntArray, width: Int, height: Int): CircleSample {
        var bright = 0
        var dark = 0
        var mid = 0
        var missing = false
        for (cx in soloCircles) {
            val mean = meanLuma(pixels, width, height, scaleX(cx, width), scaleY(930, height))
            if (mean == null) {
                missing = true
                continue
            }
            when {
                mean > 90 -> bright++
                mean < 45 -> dark++
                else -> mid++
            }
        }
        val classifiable = !missing && mid == 0 && bright + dark == soloCircles.size
        val report = if (classifiable) {
            "bright=$bright/${soloCircles.size} dark=$dark/${soloCircles.size}"
        } else {
            null
        }
        return CircleSample(bright, dark, classifiable, report)
    }

    private fun formatScores(
        reds: Int,
        turn: HudText.Turn,
        activate: ActivateSample,
        circles: CircleSample,
    ): String {
        fun n(value: Double) = String.format(java.util.Locale.US, "%.2f", value)
        return "opponentReds=$reds your=${n(turn.yourScore)} opponent=${n(turn.opponentScore)} " +
            "time=${n(turn.timeScore)} activateLuma=${activate.mean} " +
            "activateBright=${n(activate.brightFraction)} circlesBright=${circles.bright} " +
            "circlesDark=${circles.dark} circlesClassifiable=${if (circles.classifiable) "yes" else "no"}"
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

/** Tap point for a left-card ACTIVATE word. Null means do not tap. */
object SoloBooster {
    data class Tap(val x: Float, val y: Float)

    fun decision(
        hud: HudObservation,
        controlEnabled: Boolean,
        extraMoveAvailable: Boolean,
        latched: Boolean,
    ): String = when {
        latched -> "latched"
        !controlEnabled -> "toggle-off"
        hud.turnState == HudObservation.TURN_OPPONENT -> "opponent"
        !hud.activateWord -> "no-activate-word"
        extraMoveAvailable -> "wait-extra"
        else -> "tap"
    }

    fun plan(
        hud: HudObservation,
        width: Int,
        height: Int,
        controlEnabled: Boolean,
        extraMoveAvailable: Boolean = false,
    ): Tap? {
        val ourTurn = hud.turnState != HudObservation.TURN_OPPONENT
        if (!BoosterMonitor.mayTap(controlEnabled, hud.activateWord, ourTurn, extraMoveAvailable)) return null
        val (x, y) = HudReader.activateCenter(width, height)
        if (x < 0f || y < 0f) return null
        return Tap(x, y)
    }
}
