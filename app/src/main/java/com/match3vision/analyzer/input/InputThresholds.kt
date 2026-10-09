package com.match3vision.analyzer.input

import com.match3vision.analyzer.vision.VisionThresholds

/**
 * Gates and timing for AUTOMATIC INPUT ENGINE V1.
 * Vision thresholds are reused from [VisionThresholds] — never loosened here.
 */
object InputThresholds {
    /** Minimum MoveEvaluation.confidence required before any gesture. */
    const val MIN_MOVE_CONFIDENCE = 0.50f

    /** Wait after gesture for cascade/animation to settle before re-capture. */
    const val ANIMATION_WAIT_MS = 650L

    /** Max consecutive VERIFY_RESULT failures before FAIL-SAFE STOP. */
    const val MAX_VERIFY_FAILURES = 2

    /** Center-to-center swipe. Long enough to land in the neighbour cell. */
    const val SWIPE_DURATION_MS = 280L

    const val MIN_GRID_CONFIDENCE = VisionThresholds.MIN_GRID_CONFIDENCE
    const val MIN_BOARD_CONFIDENCE = VisionThresholds.MIN_BOARD_CONFIDENCE
    const val MAX_UNKNOWN_COUNT = VisionThresholds.MAX_UNKNOWN_COUNT
}
