package com.match3vision.analyzer.input

import kotlin.math.hypot

/**
 * Where the TESZT ÉRINTÉS ring is, in the same pixels as MotionEvent rawX/rawY.
 *
 * The nominal point is the centre of the full display. The overlay window can
 * still be shifted by a status bar. The comparison uses the view's
 * getLocationOnScreen plus the canvas centre, not the nominal point alone.
 * [CoordinateSelfCheck.MEASURED_TOLERANCE_PX] is unchanged.
 */
object CalibrationTarget {
    /** Outer high-contrast ring. Larger than the 48 px valid zone on purpose. */
    const val RING_RADIUS_PX = 120f

    /**
     * Taps farther than this from the ring centre are not a calibration sample.
     * They are ignored. They are not stored as OBSERVED_MISMATCH.
     */
    const val SCORE_RADIUS_PX = 150f

    /** Overlay closes itself after a measured hit so the next tap reaches the game. */
    const val AUTO_DISMISS_MS = 1_000L

    const val LABEL = "IDE ÉRINTS"
    const val INSTRUCTION = "Érintsd a fehér-piros kör közepét. Nincs játékérintés."
    const val CLOSE_LABEL = "KÉSZ"

    fun nominalPoint(screenWidth: Int, screenHeight: Int): Pair<Float, Float> =
        CalibrationTouch.expectedPoint(screenWidth, screenHeight)

    /** [viewLeft]/[viewTop] are getLocationOnScreen. [drawX]/[drawY] are canvas pixels. */
    fun onScreenPoint(viewLeft: Int, viewTop: Int, drawX: Float, drawY: Float): Pair<Float, Float> =
        viewLeft + drawX to viewTop + drawY

    fun distance(rawX: Float, rawY: Float, targetX: Float, targetY: Float): Float =
        hypot(rawX - targetX, rawY - targetY)

    /** True when the finger is inside or near the ring. Far taps are not scored. */
    fun scoresTap(tapX: Float, tapY: Float, targetX: Float, targetY: Float): Boolean =
        distance(tapX, tapY, targetX, targetY) <= SCORE_RADIUS_PX

    fun resultLine(within: Boolean, distancePx: Float): String =
        if (within) {
            "KALIBRÁCIÓ OK távolság=${"%.0f".format(distancePx)} px"
        } else {
            "eltérés ${"%.0f".format(distancePx)} px — érintsd újra a kör közepét"
        }
}

/**
 * Full-screen calibration window in display pixels.
 * Explicit size and a zero inset fit keep the canvas origin on the display origin.
 * TYPE_ACCESSIBILITY_OVERLAY is not used: only an AccessibilityService may add it.
 */
object CalibrationWindowPlan {
    const val FIT_INSETS_NONE = 0

    data class Plan(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val fitInsetsTypes: Int,
        val fitInsetsSides: Int,
        val layoutInScreen: Boolean,
        val layoutNoLimits: Boolean,
        val cutoutAlways: Boolean,
    )

    fun plan(screenWidth: Int, screenHeight: Int): Plan = Plan(
        x = 0,
        y = 0,
        width = screenWidth,
        height = screenHeight,
        fitInsetsTypes = FIT_INSETS_NONE,
        fitInsetsSides = FIT_INSETS_NONE,
        layoutInScreen = true,
        layoutNoLimits = true,
        cutoutAlways = true,
    )
}

/**
 * Own-app frames and the calibration dim are kept out of the live ring.
 */
object LiveFrameFilter {
    fun blocked(
        ownUi: Boolean,
        calibrationVisible: Boolean,
        calibrationDismissWallMs: Long,
        frameTimestampMs: Long,
        transitionSkipMs: Long = DiagnosticHistory.TRANSITION_SKIP_MS,
    ): Boolean {
        if (ownUi || calibrationVisible) return true
        if (calibrationDismissWallMs <= 0L) return false
        return frameTimestampMs < calibrationDismissWallMs + transitionSkipMs
    }

    fun frameSource(ownUi: Boolean, calibrationBlocked: Boolean): String = when {
        calibrationBlocked && !ownUi -> DiagnosticBundle.SOURCE_CALIBRATION
        ownUi -> DiagnosticBundle.SOURCE_OWN_UI
        else -> DiagnosticBundle.SOURCE_IN_GAME
    }
}
