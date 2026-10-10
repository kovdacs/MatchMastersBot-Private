package com.match3vision.analyzer.play

import com.match3vision.analyzer.vision.VisionThresholds

/**
 * A board with a few unread cells (booster skulls, white gems) is still
 * playable. Legal moves already skip UNKNOWN cells. More than [LIMIT]
 * unknowns still holds.
 */
object PlayUnknown {
    const val LIMIT = 3

    fun forPlay(
        validationPass: Boolean,
        unknownCount: Int,
        gridConfidence: Float,
        holdReason: String?,
    ): Boolean {
        if (unknownCount > LIMIT) return false
        if (validationPass) return true
        if (unknownCount <= VisionThresholds.MAX_UNKNOWN_COUNT) return false
        val onlyUnknown = holdReason?.contains("unknownCount") == true
        return onlyUnknown && gridConfidence >= VisionThresholds.MIN_GRID_CONFIDENCE
    }
}
