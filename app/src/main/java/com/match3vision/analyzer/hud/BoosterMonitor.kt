package com.match3vision.analyzer.hud

/**
 * The left-card ACTIVATE word is tapped when the bubble toggle is on, the
 * turn is not the opponent's, and no extra-move swap is available. The toggle
 * defaults on. A bright rectangle without the word is not a tap.
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

    fun mayTap(
        controlEnabled: Boolean,
        activateWord: Boolean,
        ourTurn: Boolean,
        extraMoveAvailable: Boolean,
    ): Boolean = controlEnabled && activateWord && ourTurn && !extraMoveAvailable
}

/** Bubble toggle. Default on for solo and PvP. The owner can turn it off. */
object BoosterControl {
    @Volatile
    var enabled: Boolean = true

    fun label(): String = if (enabled) "BOOSTER: BE" else "BOOSTER: KI"
}
