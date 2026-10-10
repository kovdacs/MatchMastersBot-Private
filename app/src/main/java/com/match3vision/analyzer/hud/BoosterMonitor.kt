package com.match3vision.analyzer.hud

/**
 * The left-card ACTIVATE word is tapped when the bubble toggle is on and the
 * turn is not the opponent's. An extra-move swap does not delay the tap, and
 * the first swipe of the game does not either. The toggle defaults on. A
 * bright rectangle without the word is not a tap.
 */
object BoosterMonitor {
    const val CONTROL_OFF = "off"
    const val CHARGING = "CHARGING"
    const val READY = "READY"
    const val UNKNOWN = "UNKNOWN"

    fun phase(fill: String): String = when (fill) {
        "empty", "partial" -> CHARGING
        "full" -> READY
        else -> UNKNOWN
    }

    @Suppress("UNUSED_PARAMETER")
    fun mayTap(
        controlEnabled: Boolean,
        activateWord: Boolean,
        ourTurn: Boolean,
        extraMoveAvailable: Boolean,
        soloPositive: Boolean,
        yourTurn: Boolean,
        swipesVerified: Int,
        selfCheckMeasured: Boolean,
        boosterReady: Boolean = false,
    ): Boolean = controlEnabled &&
        (activateWord || boosterReady) &&
        ourTurn &&
        selfCheckMeasured &&
        (soloPositive || yourTurn)
}

/**
 * Bubble toggle. Default on for solo and PvP. The owner can turn it off.
 * [equippedId] is the only source for [com.match3vision.analyzer.input.FiveMoveSession.noteEquippedBooster].
 * Empty means the id is unknown. ACTIVATE is still tapped. Only a known id that needs a target is blocked.
 */
object BoosterControl {
    @Volatile
    var enabled: Boolean = true

    @Volatile
    var equippedId: String = ""

    fun label(): String = if (enabled) "BOOSTER: BE" else "BOOSTER: KI"
}
