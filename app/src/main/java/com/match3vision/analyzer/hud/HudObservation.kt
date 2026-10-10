package com.match3vision.analyzer.hud

import com.match3vision.analyzer.moves.TurnClock
import com.match3vision.analyzer.vision.TileColor

/**
 * One frame's HUD reading.
 *
 * A classifiable row of bright move circles is the solo signal, even when the
 * opponent-gem counter is high. An uncertain frame is still [soloLayout] so
 * lookahead and ACTIVATE can run; the session stops only on a positive
 * Opponent's Turn. [movesRemaining] is the bright-circle count when that row
 * is trusted, and null when the row was not read. An empty [legendPoints] map
 * means the digits were not read, so the ranker uses the official default.
 */
data class HudObservation(
    val timer: String = TurnClock.NOT_DETECTABLE,
    val moves: String = NOT_DETECTABLE,
    val rounds: String = NOT_DETECTABLE,
    val mode: String = MODE_UNKNOWN,
    val boosterFill: String = "unknown",
    val boosterPhase: String = BoosterMonitor.UNKNOWN,
    val blueFactor: Double = 1.0,
    val soloLayout: Boolean = false,
    val activate: String = NOT_DETECTABLE,
    val boosterTarget: String = "unknown",
    val legend: String = NOT_DETECTABLE,
    val yourTurn: String = NOT_DETECTABLE,
    val movesRemaining: Int? = null,
    /** Bright move circles when the row was classifiable. Null when it was not. */
    val circlesBright: Int? = null,
    val circlesClassifiable: Boolean = false,
    /** True only when the left-card glyph is the word ACTIVATE. A bright rect is not enough. */
    val activateWord: Boolean = false,
    /** This frame's ACTIVATE shape score, before the 5-frame window. */
    val activateScore: Double = 0.0,
    val activateFloor: Double = 0.80,
    val activateRect: String = "none",
    /** Fraction of the ACTIVATE rect above the bright luma cut. Not a tap by itself. */
    val activateBrightFraction: Double = 0.0,
    /** The booster pill is full on this frame, whether or not the word was read. */
    val barFull: Boolean = false,
    /** Word on any of the last 5 frames, or a full bar on 3 of those frames. */
    val boosterReady: Boolean = false,
    /**
     * True for a circle row or a positively read solo layout.
     * An uncertain frame can still be [soloLayout] for lookahead and is not solo-positive.
     */
    val soloPositive: Boolean = false,
    val legendPoints: Map<TileColor, Int> = emptyMap(),
    /** your-turn, time-left, opponent-turn, or not detectable. */
    val turnState: String = NOT_DETECTABLE,
    val timeLeftSeconds: Int? = null,
    /** Read xN. Null means the bonus is not applied. */
    val multiplier: Int? = null,
    val multiplierNote: String = NOT_DETECTABLE,
    /**
     * OPPONENT, YOUR, TIME, or SOLO when that reading is positive.
     * UNKNOWN when the frame is unrecognized or ambiguous.
     */
    val hudState: String = HUD_UNKNOWN,
    /** Classifier inputs. Present so a live export can be tuned. */
    val hudScores: String = "opponentReds=0 your=0.00 opponent=0.00 time=0.00 " +
        "activateLuma=0 activateBright=0.00 circlesBright=0 circlesDark=0 circlesClassifiable=no",
    /** out-of-moves or opponent-wins. Null while the match is still going. */
    val endScreen: String? = null,
) {
    fun playerTurn(): Boolean = turnState == TURN_YOUR || turnState == TURN_TIME

    /** Blue term scale. 1 when the multiplier was not read. */
    fun blueMultiplier(): Int = multiplier ?: 1

    fun log(): String =
        "timer=$timer moves=$moves rounds=$rounds mode=$mode solo=${if (soloLayout) "yes" else "no"} " +
            "boosterFill=$boosterFill boosterPhase=$boosterPhase activate=$activate " +
            "boosterTarget=$boosterTarget boosterControl=${if (BoosterControl.enabled) "on" else BoosterMonitor.CONTROL_OFF} " +
            "blueFactor=$blueFactor legend=$legend yourTurn=$yourTurn " +
            "turnState=$turnState timeLeft=${timeLeftSeconds?.toString() ?: NOT_DETECTABLE} " +
            "multiplier=$multiplierNote " +
            "movesRemaining=${movesRemaining?.toString() ?: NOT_DETECTABLE} " +
            "circlesBright=${circlesBright?.toString() ?: NOT_DETECTABLE} " +
            "circlesClassifiable=${if (circlesClassifiable) "yes" else "no"} " +
            "activateWord=${if (activateWord) "yes" else "no"} " +
            "barFull=${if (barFull) "yes" else "no"} boosterReady=${if (boosterReady) "yes" else "no"} " +
            "activateScore=$activateScore activateFloor=$activateFloor activateRect=$activateRect " +
            "soloPositive=${if (soloPositive) "yes" else "no"} " +
            "endScreen=${endScreen ?: "none"} " +
            "hudState=$hudState hudScores=$hudScores"

    companion object {
        const val NOT_DETECTABLE = "not detectable"
        const val MODE_UNKNOWN = "mode unknown"
        const val TURN_YOUR = "your-turn"
        const val TURN_OPPONENT = "opponent-turn"
        const val TURN_TIME = "time-left"
        const val HUD_UNKNOWN = "UNKNOWN"
        const val HUD_OPPONENT = "OPPONENT"
        const val HUD_YOUR = "YOUR"
        const val HUD_TIME = "TIME"
        const val HUD_SOLO = "SOLO"

        fun hudStateFor(turnState: String, soloLayout: Boolean): String = when {
            turnState == TURN_OPPONENT -> HUD_OPPONENT
            turnState == TURN_YOUR -> HUD_YOUR
            turnState == TURN_TIME -> HUD_TIME
            soloLayout -> HUD_SOLO
            else -> HUD_UNKNOWN
        }

        /** Recognized solo frame. Legend digits are not invented. */
        fun solo(
            activateVisible: Boolean = false,
            movesRemaining: Int? = null,
            activateWord: Boolean = false,
        ): HudObservation {
            val full = activateVisible || activateWord
            return HudObservation(
                mode = "solo",
                soloLayout = true,
                boosterFill = if (activateWord) "ACTIVATE" else if (full) "full" else "unknown",
                boosterPhase = if (full) BoosterMonitor.READY else BoosterMonitor.UNKNOWN,
                activate = if (full) "yes" else "no",
                boosterTarget = if (activateWord) "none" else "unknown",
                blueFactor = if (full) com.match3vision.analyzer.moves.PlayMoveRanker.FULL_BAR_BLUE_FACTOR else 1.0,
                legend = "legend default",
                movesRemaining = movesRemaining,
                circlesBright = movesRemaining,
                circlesClassifiable = movesRemaining != null,
                activateWord = activateWord,
                soloPositive = true,
                hudState = HUD_SOLO,
            )
        }

        fun pvp(
            turnState: String = NOT_DETECTABLE,
            timeLeftSeconds: Int? = null,
            multiplier: Int? = null,
            activateWord: Boolean = false,
        ): HudObservation {
            val player = turnState == TURN_YOUR || turnState == TURN_TIME
            val label = when (turnState) {
                TURN_YOUR -> "Your Turn"
                TURN_OPPONENT -> "Opponent's Turn"
                TURN_TIME -> "Time Left: ${timeLeftSeconds ?: ""}".trim()
                else -> NOT_DETECTABLE
            }
            return HudObservation(
                timer = if (turnState == TURN_TIME && timeLeftSeconds != null) {
                    "Time Left: $timeLeftSeconds"
                } else {
                    TurnClock.NOT_DETECTABLE
                },
                mode = "pvp",
                soloLayout = false,
                boosterFill = if (activateWord) "ACTIVATE" else "unknown",
                boosterPhase = if (activateWord) BoosterMonitor.READY else BoosterMonitor.UNKNOWN,
                activate = if (activateWord && player) "yes" else "no",
                boosterTarget = if (activateWord && player) "none" else "unknown",
                activateWord = activateWord,
                yourTurn = label,
                turnState = turnState,
                timeLeftSeconds = timeLeftSeconds,
                multiplier = multiplier,
                multiplierNote = if (multiplier != null) "x$multiplier" else NOT_DETECTABLE,
                hudState = hudStateFor(turnState, soloLayout = false),
            )
        }

        val UNKNOWN = HudObservation()
    }
}
