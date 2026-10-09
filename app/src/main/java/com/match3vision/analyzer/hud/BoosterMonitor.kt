package com.match3vision.analyzer.hud

/**
 * ACTIVATE is tapped only for the recognized solo layout, and only when the
 * bubble toggle is on. The toggle defaults off. Any other HUD is log-only.
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

    fun mayTap(soloLayout: Boolean, activateVisible: Boolean, controlEnabled: Boolean): Boolean =
        soloLayout && activateVisible && controlEnabled
}

/** Bubble toggle. Default off, so a session never taps ACTIVATE unless the owner turns it on. */
object BoosterControl {
    @Volatile
    var enabled: Boolean = false

    fun label(): String = if (enabled) "BOOSTER: BE" else "BOOSTER: KI"
}
