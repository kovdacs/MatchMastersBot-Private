package com.match3vision.analyzer.input

/**
 * Test-only grant for continuous INDÍTÁS.
 *
 * Production [AutoPlayController.onStartRequested] does not call this.
 * A recorded self-check still has alignmentProven false.
 */
object PlayPermit {
    fun allowContinuousStart() {
        CoordinateSelfCheck.record(
            expectedX = 100f,
            expectedY = 200f,
            screenWidth = 1080,
            screenHeight = 2400,
            frameWidth = 1080,
            frameHeight = 2400,
            rotation = 0,
            observedX = 100f,
            observedY = 200f,
        )
    }
}
