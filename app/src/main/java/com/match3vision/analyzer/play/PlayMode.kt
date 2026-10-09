package com.match3vision.analyzer.play

import com.match3vision.analyzer.hud.HudObservation

/**
 * What this frame is asking the bot to do.
 * [BASIC] is an unrecognized layout: keep swiping, never stop because of it.
 */
enum class PlayMode {
    SOLO,
    TIMER,
    PVP,
    BASIC,
    ;

    companion object {
        fun classify(hud: HudObservation): PlayMode {
            if (!PlayFlags.modeAdapt) return BASIC
            val circles = hud.circlesClassifiable && hud.circlesBright != null
            if (hud.soloPositive || hud.hudState == HudObservation.HUD_SOLO || circles) {
                return SOLO
            }
            val timer = hud.timeLeftSeconds != null ||
                hud.turnState == HudObservation.TURN_TIME ||
                hud.hudState == HudObservation.HUD_TIME
            if (timer) return TIMER
            val pvp = hud.turnState == HudObservation.TURN_YOUR ||
                hud.turnState == HudObservation.TURN_OPPONENT ||
                hud.hudState == HudObservation.HUD_YOUR ||
                hud.hudState == HudObservation.HUD_OPPONENT ||
                hud.mode.equals("pvp", ignoreCase = true)
            if (pvp) return PVP
            return BASIC
        }
    }
}
