package com.match3vision.analyzer.moves

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.board.GameState
import com.match3vision.analyzer.evaluation.MoveEvaluation
import com.match3vision.analyzer.evaluation.MoveEvaluator
import com.match3vision.analyzer.vision.ValidationResult
import com.match3vision.analyzer.vision.VisionResult
import com.match3vision.analyzer.vision.VisionThresholds
import com.match3vision.analyzer.vision.VisionValidator

/**
 * MOVE ANALYSIS ENGINE V1 — read-only.
 *
 * Runs only when Vision validation PASS **and**
 * gridConf ≥ [VisionThresholds.MIN_GRID_CONFIDENCE] (0.98) **and**
 * boardConf ≥ [VisionThresholds.MIN_BOARD_CONFIDENCE] (0.95) **and**
 * unknownCount ≤ [VisionThresholds.MAX_UNKNOWN_COUNT] (1).
 *
 * Otherwise returns HOLD — Decision AI blocked (no moves). Never actuates.
 */
class MoveAnalysisEngine(
    private val moveGenerator: LegalMoveGenerator = LegalMoveGenerator(),
    private val moveEvaluator: MoveEvaluator = MoveEvaluator(),
    private val visionValidator: VisionValidator = VisionValidator(),
    private val topN: Int = 5,
) {
    data class AnalysisResult(
        val blocked: Boolean,
        val holdReason: String?,
        val topMoves: List<MoveEvaluation>,
        /**
         * Real-play order: extra move, then blue gems, then total gems, then
         * lower on the board. Empty when the vision gate blocks the decision.
         * [topMoves] stays the older EV list so existing diagnostics keep it.
         */
        val play: PlayMoveRanker.Ranking = PlayMoveRanker.EMPTY_RANKING,
    ) {
        /** TOP-5 (or fewer) ranked by EV/score descending. */
        val top5: List<MoveEvaluation> get() = topMoves
        val hasMoves: Boolean get() = topMoves.isNotEmpty()
        val playTop3: List<PlayMoveRanker.Candidate> get() = play.top3
    }

    fun analyze(vision: VisionResult, opponentScore: Float = 0f): AnalysisResult {
        val thresholdGate = visionValidator.validate(
            boardConfidence = vision.boardConfidence,
            gridConfidence = vision.gridConfidence,
            unknownCount = vision.unknownCount,
        )
        if (!vision.validation.isPass || thresholdGate !is ValidationResult.Pass) {
            val reason = when {
                vision.validation is ValidationResult.Hold ->
                    (vision.validation as ValidationResult.Hold).reason
                thresholdGate is ValidationResult.Hold -> thresholdGate.reason
                else -> HOLD_BLOCKED
            }
            return blocked(reason)
        }
        return analyzeBoard(
            board = Board.fromVision(vision.board),
            gatePass = true,
            gridConfidence = vision.gridConfidence,
            boardConfidence = vision.boardConfidence,
            unknownCount = vision.unknownCount,
            opponentScore = opponentScore,
        )
    }

    fun analyze(state: GameState, opponentScore: Float = 0f): AnalysisResult =
        analyzeBoard(
            board = state.board,
            gatePass = state.vision.gatePass,
            gridConfidence = state.vision.gridConfidence,
            boardConfidence = state.vision.boardConfidence,
            unknownCount = state.vision.unknownCount,
            opponentScore = opponentScore,
            holdHint = state.vision.holdReason,
        )

    fun analyzeBoard(
        board: Board,
        gatePass: Boolean,
        gridConfidence: Float,
        boardConfidence: Float,
        unknownCount: Int,
        opponentScore: Float = 0f,
        holdHint: String? = null,
    ): AnalysisResult {
        val thresholdGate = visionValidator.validate(
            boardConfidence = boardConfidence,
            gridConfidence = gridConfidence,
            unknownCount = unknownCount,
        )
        if (!gatePass || thresholdGate !is ValidationResult.Pass) {
            val reason = when {
                !gatePass && !holdHint.isNullOrBlank() -> holdHint
                thresholdGate is ValidationResult.Hold -> thresholdGate.reason
                else -> HOLD_BLOCKED
            }
            return blocked(reason)
        }
        if (unknownCount > VisionThresholds.MAX_UNKNOWN_COUNT) {
            return blocked(HOLD_BLOCKED)
        }

        val ranked = moveGenerator.generate(board)
            .map { moveEvaluator.evaluate(board, it, opponentScore) }
            .filter { it.expectedValue.isFinite() }
            .sortedWith(
                compareByDescending<MoveEvaluation> { it.expectedValue }
                    .thenByDescending { it.totalScore }
                    .thenBy { it.move.toString() },
            )
            .take(topN)

        return AnalysisResult(
            blocked = false,
            holdReason = null,
            topMoves = ranked,
            play = PlayMoveRanker(moveGenerator).rank(board),
        )
    }

    private fun blocked(reason: String) = AnalysisResult(
        blocked = true,
        holdReason = reason.ifBlank { HOLD_BLOCKED },
        topMoves = emptyList(),
    )

    companion object {
        const val HOLD_BLOCKED = "HOLD — Decision AI blocked"
    }
}
