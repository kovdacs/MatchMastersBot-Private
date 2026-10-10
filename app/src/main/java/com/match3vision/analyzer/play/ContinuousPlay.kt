package com.match3vision.analyzer.play

/**
 * Stop and wait rules for a game that is not a fixed move count.
 * Solo stops when the circles reach zero. Timer plays while time remains.
 * PvP waits through the opponent's turn. An unknown layout never stops.
 */
object ContinuousPlay {
    const val STOP = "stop"
    const val WAIT = "wait"
    const val PLAY = "play"

    fun opponentAction(mode: PlayMode): String = when {
        !PlayFlags.pvpResume -> STOP
        mode == PlayMode.PVP -> WAIT
        mode == PlayMode.BASIC -> PLAY
        else -> STOP
    }

    fun stopOnZeroCircles(mode: PlayMode, timeLeftSeconds: Int?): Boolean {
        if (!PlayFlags.continuous) return true
        if (mode == PlayMode.TIMER && (timeLeftSeconds == null || timeLeftSeconds > 0)) return false
        if (mode == PlayMode.PVP) return false
        return true
    }

    fun timeOut(mode: PlayMode, timeLeftSeconds: Int?): Boolean =
        PlayFlags.continuous && mode == PlayMode.TIMER && timeLeftSeconds == 0
}
