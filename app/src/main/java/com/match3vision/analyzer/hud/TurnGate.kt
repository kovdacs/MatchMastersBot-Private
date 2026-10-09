package com.match3vision.analyzer.hud

/**
 * The session stops only when the turn bar positively reads Opponent's Turn.
 * An unrecognized or ambiguous HUD is logged as UNKNOWN and play continues.
 */
object TurnGate {
    fun refusal(hud: HudObservation): String? {
        if (hud.turnState != HudObservation.TURN_OPPONENT) return null
        return "STOP — Opponent's Turn"
    }

    /** A board change during the opponent's turn is not our verified move. */
    fun allowsVerification(hud: HudObservation): Boolean = refusal(hud) == null
}
