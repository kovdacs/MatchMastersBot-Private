package com.match3vision.analyzer.overlay

import com.match3vision.analyzer.input.FiveMoveArm

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

    fun fiveLabel(verified: Int, max: Int): String = "10 LÉPÉS $verified/$max"

    /** Shown on the collapsed chip while a 10-move session is in progress. */
    fun collapsedStatus(
        fiveActive: Boolean,
        fiveLabel: String,
        chipNotice: String,
        fallback: String,
    ): String = if (fiveActive) {
        "${FiveMoveArm.DO_NOT_TOUCH}\n$fiveLabel"
    } else {
        chipNotice.ifBlank { fallback }
    }
}
