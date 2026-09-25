package com.match3vision.analyzer.vision

/**
 * PASS only if board confidence, grid confidence, and unknown count meet thresholds.
 * Otherwise HOLD — Decision AI blocked (message only; no Decision AI code in Phase 2).
 */
class VisionValidator(
    private val minBoardConfidence: Float = VisionThresholds.MIN_BOARD_CONFIDENCE,
    private val minGridConfidence: Float = VisionThresholds.MIN_GRID_CONFIDENCE,
    private val maxUnknownCount: Int = VisionThresholds.MAX_UNKNOWN_COUNT,
) {

    fun validate(
        boardConfidence: Float,
        gridConfidence: Float,
        unknownCount: Int,
    ): ValidationResult {
        if (gridConfidence < minGridConfidence) {
            return ValidationResult.Hold(
                "HOLD: grid confidence %.3f < %.3f (Decision AI blocked)".format(
                    gridConfidence,
                    minGridConfidence,
                ),
            )
        }
        if (boardConfidence < minBoardConfidence) {
            return ValidationResult.Hold(
                "HOLD: board confidence %.3f < %.3f (Decision AI blocked)".format(
                    boardConfidence,
                    minBoardConfidence,
                ),
            )
        }
        if (unknownCount > maxUnknownCount) {
            return ValidationResult.Hold(
                "HOLD: unknownCount $unknownCount > $maxUnknownCount (Decision AI blocked)",
            )
        }
        return ValidationResult.Pass
    }
}
