package com.match3vision.analyzer.input

import com.match3vision.analyzer.hud.HudObservation

/**
 * A stale-frame TOCTOU block means the gesture was not sent.
 * It is not a failed dispatch and it does not stop the session.
 */
object FreshFrameDispatch {
    const val BOOSTER_GIVE_UP_MS = 10_000L

    fun isStaleFrameBlock(reason: String): Boolean =
        reason.contains("stale frame", ignoreCase = true)

    enum class Booster { SEND, WAIT, GIVE_UP }

    /**
     * Send only when this frame is fresh, the gates pass, ACTIVATE is still
     * ours, and the recheck allows it. Otherwise wait. After
     * [BOOSTER_GIVE_UP_MS] the booster is skipped and swipes continue.
     */
    fun booster(
        elapsedMs: Long,
        ageMs: Long,
        ageLimitMs: Long,
        gatesPass: Boolean,
        showsActivateOrYourTurn: Boolean,
        recheckAllow: Boolean,
    ): Booster {
        if (elapsedMs >= BOOSTER_GIVE_UP_MS) return Booster.GIVE_UP
        val fresh = ageMs >= 0L && ageMs <= ageLimitMs
        if (fresh && gatesPass && showsActivateOrYourTurn && recheckAllow) return Booster.SEND
        return Booster.WAIT
    }

    fun showsActivate(hud: HudObservation): Boolean {
        val activate = hud.activateWord || hud.boosterReady
        val yours = hud.soloPositive || hud.playerTurn()
        return activate && yours
    }
}
