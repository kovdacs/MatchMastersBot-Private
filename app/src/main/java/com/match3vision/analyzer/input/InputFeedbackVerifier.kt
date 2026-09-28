package com.match3vision.analyzer.input

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.board.GameState
import com.match3vision.analyzer.vision.ValidationResult
import com.match3vision.analyzer.vision.VisionResult
import com.match3vision.analyzer.vision.VisionThresholds
import com.match3vision.analyzer.vision.VisionValidator

/**
 * Post-input verification: after animation wait + new frame, confirm the board
 * actually changed. Invalid / unknown / uncertain frames → FAIL-SAFE STOP
 * (no blind retry).
 */
class InputFeedbackVerifier(
    private val visionValidator: VisionValidator = VisionValidator(),
) {
    sealed class VerifyOutcome {
        data class BoardChanged(val beforeHash: Long, val afterHash: Long) : VerifyOutcome()
        data class Hold(val reason: String) : VerifyOutcome()
        data class Stop(val reason: String) : VerifyOutcome()
    }

    fun verify(
        beforeBoardHash: Long,
        afterVision: VisionResult,
    ): VerifyOutcome {
        if (afterVision.validation is ValidationResult.Hold) {
            return VerifyOutcome.Stop(
                "invalid new frame: ${(afterVision.validation as ValidationResult.Hold).reason}",
            )
        }
        val gate = visionValidator.validate(
            boardConfidence = afterVision.boardConfidence,
            gridConfidence = afterVision.gridConfidence,
            unknownCount = afterVision.unknownCount,
        )
        if (gate is ValidationResult.Hold) {
            return VerifyOutcome.Stop("invalid new frame: ${gate.reason}")
        }
        if (afterVision.unknownCount > VisionThresholds.MAX_UNKNOWN_COUNT) {
            return VerifyOutcome.Stop(
                "fail-safe: unknownCount=${afterVision.unknownCount}",
            )
        }
        if (afterVision.gridConfidence < VisionThresholds.MIN_GRID_CONFIDENCE ||
            afterVision.boardConfidence < VisionThresholds.MIN_BOARD_CONFIDENCE
        ) {
            return VerifyOutcome.Stop("fail-safe: low confidence after input")
        }

        val afterBoard = Board.fromVision(afterVision.board)
        if (!afterBoard.isValidStructure()) {
            return VerifyOutcome.Stop("fail-safe: board structure invalid")
        }
        val afterHash = afterBoard.contentHash()
        if (afterHash == beforeBoardHash) {
            return VerifyOutcome.Stop("board unchanged after input — no blind retry")
        }
        return VerifyOutcome.BoardChanged(beforeBoardHash, afterHash)
    }

    fun verifyGameState(beforeBoardHash: Long, after: GameState): VerifyOutcome {
        if (!after.vision.gatePass) {
            return VerifyOutcome.Stop(
                "invalid new frame: ${after.vision.holdReason ?: "vision gate HOLD"}",
            )
        }
        if (after.vision.unknownCount > VisionThresholds.MAX_UNKNOWN_COUNT) {
            return VerifyOutcome.Stop(
                "fail-safe: unknownCount=${after.vision.unknownCount}",
            )
        }
        if (after.vision.gridConfidence < VisionThresholds.MIN_GRID_CONFIDENCE ||
            after.vision.boardConfidence < VisionThresholds.MIN_BOARD_CONFIDENCE
        ) {
            return VerifyOutcome.Stop("fail-safe: low confidence after input")
        }
        if (!after.board.isValidStructure()) {
            return VerifyOutcome.Stop("fail-safe: board structure invalid")
        }
        if (after.boardHash == beforeBoardHash) {
            return VerifyOutcome.Stop("board unchanged after input — no blind retry")
        }
        return VerifyOutcome.BoardChanged(beforeBoardHash, after.boardHash)
    }
}
