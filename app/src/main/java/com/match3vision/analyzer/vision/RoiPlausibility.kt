package com.match3vision.analyzer.vision

/**
 * Phone-sized frames must not accept a ROI that includes the header or the
 * whole screen. This only tightens the gate. It does not lower
 * [VisionThresholds] and it does not retune the lattice score.
 *
 * Small synthetic boards are the board itself, so top 0 is not the header.
 * The check runs when the frame is at least [PHONE_FRAME_MIN_HEIGHT] px tall.
 */
object RoiPlausibility {
    const val HOLD_REASON = "HOLD: ROI IMPLAUSIBLE"
    const val MAX_ASPECT = 1.3f
    const val PHONE_FRAME_MIN_HEIGHT = 1600

    fun reject(frameHeight: Int, roiTop: Int, roiWidth: Int, roiHeight: Int): Boolean {
        if (frameHeight < PHONE_FRAME_MIN_HEIGHT) return false
        if (roiWidth <= 0 || frameHeight <= 0) return true
        val topFraction = roiTop.toFloat() / frameHeight.toFloat()
        val aspect = roiHeight.toFloat() / roiWidth.toFloat()
        return topFraction == 0f || aspect > MAX_ASPECT
    }
}
