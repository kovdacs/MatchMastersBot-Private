package com.match3vision.analyzer.input

/**
 * Production writer for TESZT ÉRINTÉS.
 *
 * The bubble shows [com.match3vision.analyzer.overlay.CalibrationOverlayView]
 * and passes MotionEvent.getRawX / getRawY here. The finger lands on our
 * window. This object does not call dispatchGesture and it does not inject
 * a gesture into the game.
 *
 * [CoordinateSelfCheck.alignmentProven] stays false. A point inside
 * [CoordinateSelfCheck.MEASURED_TOLERANCE_PX] is not physical alignment.
 */
object CalibrationTouch {
    /** Centre of the calibration target. Same family as the old fixed test Y. */
    fun expectedPoint(screenWidth: Int, screenHeight: Int): Pair<Float, Float> =
        screenWidth / 2f to screenHeight * 0.45f

    fun recordRawTouch(
        rawX: Float,
        rawY: Float,
        screenWidth: Int,
        screenHeight: Int,
        frameWidth: Int,
        frameHeight: Int,
        rotation: Int,
        statusBarInsetPx: Int = 0,
        navigationBarInsetPx: Int = 0,
        cutoutInsetPx: Int = 0,
        /**
         * On-screen centre of the drawn target ([android.view.View.getLocationOnScreen]
         * plus the canvas point). When set, the 48 px check uses this instead of
         * [expectedPoint], so a status-bar shift of the window does not fail a hit.
         */
        targetX: Float? = null,
        targetY: Float? = null,
    ): CoordinateSelfCheck.Record {
        val nominal = expectedPoint(screenWidth, screenHeight)
        val expectedX = targetX ?: nominal.first
        val expectedY = targetY ?: nominal.second
        return CoordinateSelfCheck.record(
            expectedX = expectedX,
            expectedY = expectedY,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            rotation = rotation,
            statusBarInsetPx = statusBarInsetPx,
            navigationBarInsetPx = navigationBarInsetPx,
            cutoutInsetPx = cutoutInsetPx,
            observedX = rawX,
            observedY = rawY,
        )
    }
}
