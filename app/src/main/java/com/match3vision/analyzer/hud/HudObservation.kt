package com.match3vision.analyzer.hud

import com.match3vision.analyzer.moves.TurnClock
import com.match3vision.analyzer.vision.TileColor

/**
 * One frame's HUD reading.
 *
 * [soloLayout] is the only gate for lookahead, legend weights, specials, and
 * ACTIVATE. Move counts, legend digits, and the timer are logged and are not
 * assumed. An empty [legendPoints] map means the digits were not read, so the
 * ranker scores 1 point per gem.
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
) {
    fun log(): String =
        "timer=$timer moves=$moves rounds=$rounds mode=$mode solo=${if (soloLayout) "yes" else "no"} " +
            "boosterFill=$boosterFill boosterPhase=$boosterPhase activate=$activate " +
            "boosterTarget=$boosterTarget boosterControl=${if (BoosterControl.enabled) "on" else BoosterMonitor.CONTROL_OFF} " +
            "blueFactor=$blueFactor legend=$legend yourTurn=$yourTurn " +
            "movesRemaining=${movesRemaining?.toString() ?: NOT_DETECTABLE}"

    companion object {
        const val NOT_DETECTABLE = "not detectable"
        const val MODE_UNKNOWN = "mode unknown"

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
                legend = "not detectable; gemScore=1 per gem",
                movesRemaining = movesRemaining,
            )
        }

        val UNKNOWN = HudObservation()
    }
}
