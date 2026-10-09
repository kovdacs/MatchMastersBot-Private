package com.match3vision.analyzer.hud

/**
 * PvP may be touched only while the turn bar reads the player's turn.
 * Solo and unrecognized non-PvP frames keep the existing session.
 */
object TurnGate {
    fun refusal(hud: HudObservation): String? {
        if (hud.mode != "pvp") return null
        if (hud.playerTurn()) return null
        return if (hud.turnState == HudObservation.TURN_OPPONENT) {
            "STOP — Opponent's Turn"
        } else {
            "STOP — unrecognized PvP HUD"
        }
    }

    /** A board change during the opponent's turn is not our verified move. */
    fun allowsVerification(hud: HudObservation): Boolean = refusal(hud) == null
}
