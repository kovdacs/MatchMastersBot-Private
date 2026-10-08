package com.match3vision.analyzer.overlay

/**
 * Collapsed chip title. The status line is a separate refusal or result.
 */
object BubbleModeCaption {
    const val ANALYSIS = "ELEMZÉS"
    const val CALIBRATION_OK = "KALIBRÁCIÓ OK"

    fun title(fiveActive: Boolean, fiveLabel: String, selfCheckOk: Boolean): String = when {
        fiveActive -> fiveLabel
        selfCheckOk -> CALIBRATION_OK
        else -> ANALYSIS
    }

    fun fiveLabel(verified: Int, max: Int): String = "5 LÉPÉS $verified/$max"
}
