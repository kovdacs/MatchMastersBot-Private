package com.match3vision.analyzer.hud

import com.match3vision.analyzer.moves.TurnClock
import com.match3vision.analyzer.vision.TileColor

/**
 * One frame's HUD reading.
 *
 * [soloLayout] is the only gate for lookahead, legend weights, specials, and
 * ACTIVATE. Move counts, legend digits, and the timer are logged and are not
 * assumed. An empty [legendPoints] map means the digits were not read, so the
 * ranker uses the official default (logged as legend default).
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
        fun solo(activateVisible: Boolean = false, movesRemaining: Int? = null): HudObservation {
            val full = activateVisible
            return HudObservation(
                mode = "solo",
                soloLayout = true,
                boosterFill = if (full) "full" else "unknown",
                boosterPhase = if (full) BoosterMonitor.READY else BoosterMonitor.UNKNOWN,
                activate = if (full) "yes" else "no",
                boosterTarget = if (full) "none" else "unknown",
                blueFactor = if (full) com.match3vision.analyzer.moves.PlayMoveRanker.FULL_BAR_BLUE_FACTOR else 1.0,
                legend = "legend default",
                movesRemaining = movesRemaining,
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
