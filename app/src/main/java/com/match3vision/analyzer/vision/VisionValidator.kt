package com.match3vision.analyzer.vision

/**
 * PASS only if board confidence, grid confidence, and unknown count meet thresholds.
 * Otherwise HOLD — Decision AI blocked (message only; no Decision AI code in Phase 2).
 *
 * Check order: grid → unknown → board. High [unknownCount] often drives boardConf
 * to 0 via the unknown penalty; reporting unknown first makes live HOLD reasons
 * actionable (e.g. self-UI / overlay covering cells) without loosening gates.
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
        if (unknownCount > maxUnknownCount) {
            return ValidationResult.Hold(
                "HOLD: unknownCount $unknownCount > $maxUnknownCount (Decision AI blocked)",
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
        return ValidationResult.Pass
    }
}
