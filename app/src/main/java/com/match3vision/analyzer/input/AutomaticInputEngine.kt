package com.match3vision.analyzer.input

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.evaluation.MoveEvaluation
import com.match3vision.analyzer.vision.ValidationResult
import com.match3vision.analyzer.vision.VisionResult
import com.match3vision.analyzer.vision.VisionValidator

/**
 * AUTOMATIC INPUT ENGINE V1 — separate from Vision / MoveAnalysis / DecisionEngine.
 *
 * Executes a swipe **only** when all gates pass and [InputEnableSwitch] is explicitly
 * enabled (default DISABLED). Otherwise HOLD — Decision AI blocked, no input.
 *
 * Real gesture path: [InputGestureExecutor] (AccessibilityService preferred).
 * Never uses hardcoded pvp_board coordinates — [TouchCoordinateMapper] + grid centers.
 */
class AutomaticInputEngine(
    private val enableSwitch: InputEnableSwitch = InputEnableSwitch.disabledByDefault(),
    private val executor: InputGestureExecutor = RecordingInputGestureExecutor(ready = false),
    private val coordinateMapper: TouchCoordinateMapper = TouchCoordinateMapper(),
    private val visionValidator: VisionValidator = VisionValidator(),
    private val minMoveConfidence: Float = InputThresholds.MIN_MOVE_CONFIDENCE,
    private val stateMachine: BotStateMachine = BotStateMachine(),
    private val feedbackVerifier: InputFeedbackVerifier = InputFeedbackVerifier(visionValidator),
) {
    data class GateDecision(
        val allow: Boolean,
        val reason: String,
    )

    sealed class ExecuteResult {
        data class Executed(
            val gesture: GestureSpec,
            val beforeBoardHash: Long,
            val move: MoveEvaluation,
        ) : ExecuteResult()

        data class Held(val reason: String) : ExecuteResult()

        data class Stopped(val reason: String) : ExecuteResult()
    }

    sealed class FeedbackResult {
        data class Success(val beforeHash: Long, val afterHash: Long) : FeedbackResult()
        data class Held(val reason: String) : FeedbackResult()
        data class Stopped(val reason: String) : FeedbackResult()
    }

    fun enableSwitch(): InputEnableSwitch = enableSwitch
    fun stateMachine(): BotStateMachine = stateMachine
    fun feedbackVerifier(): InputFeedbackVerifier = feedbackVerifier
    fun isInputEnabled(): Boolean = enableSwitch.isEnabled()

    /**
     * Evaluate whether input is allowed. Does not dispatch.
     * Fail-closed: any uncertainty → HOLD.
     */
    fun evaluateGate(
        vision: VisionResult,
        move: MoveEvaluation?,
    ): GateDecision {
        if (!enableSwitch.isEnabled()) {
            return GateDecision(false, HOLD_INPUT_DISABLED)
        }
        if (!executor.isReady()) {
            return GateDecision(false, HOLD_INPUT_CHANNEL_NOT_READY)
        }
        if (!vision.validation.isPass) {
            val reason = (vision.validation as? ValidationResult.Hold)?.reason
                ?: HOLD_VISION_BLOCKED
            return GateDecision(false, reason)
        }
        val thresholdGate = visionValidator.validate(
            boardConfidence = vision.boardConfidence,
            gridConfidence = vision.gridConfidence,
            unknownCount = vision.unknownCount,
        )
        if (thresholdGate is ValidationResult.Hold) {
            return GateDecision(false, thresholdGate.reason)
        }
        if (vision.unknownCount > InputThresholds.MAX_UNKNOWN_COUNT) {
            return GateDecision(false, HOLD_TOO_MANY_UNKNOWN)
        }
        if (move == null) {
            return GateDecision(false, HOLD_NO_LEGAL_MOVE)
        }
        if (!move.expectedValue.isFinite() || move.expectedValue == Float.NEGATIVE_INFINITY) {
            return GateDecision(false, HOLD_NO_LEGAL_MOVE)
        }
        if (move.uncertain) {
            return GateDecision(false, HOLD_MOVE_UNCERTAIN)
        }
        if (move.confidence < minMoveConfidence) {
            return GateDecision(
                false,
                "HOLD — move confidence %.3f < %.3f".format(move.confidence, minMoveConfidence),
            )
        }
        if (!vision.grid.isMonotonic()) {
            return GateDecision(false, HOLD_GRID_INVALID)
        }
        return GateDecision(true, "OK")
    }

    /**
     * Attempt to execute [move] if gates pass. On failure → HOLD (no input).
     * On success returns pre-board hash for the feedback loop.
     */
    fun tryExecute(vision: VisionResult, move: MoveEvaluation?): ExecuteResult {
        val gate = evaluateGate(vision, move)
        if (!gate.allow || move == null) {
            stateMachine.onInputBlocked(gate.reason)
            return ExecuteResult.Held(gate.reason)
        }
        val gesture = try {
            coordinateMapper.toGesture(move.move, vision.grid)
        } catch (t: Throwable) {
            val reason = "HOLD — coord conversion failed: ${t.message}"
            stateMachine.onInputBlocked(reason)
            return ExecuteResult.Held(reason)
        }
        // Advance SELECT_MOVE → EXECUTE_INPUT when coming from a normal loop;
        // tests may call tryExecute from IDLE — force a safe path.
        when (stateMachine.state) {
            BotLoopState.SELECT_MOVE -> stateMachine.onMoveSelected()
            BotLoopState.EXECUTE_INPUT -> Unit
            BotLoopState.IDLE, BotLoopState.ANALYZE, BotLoopState.VALIDATE,
            BotLoopState.CAPTURE, BotLoopState.HOLD,
            -> {
                // Direct execute path (unit / one-shot): jump to EXECUTE_INPUT via reset chain.
                stateMachine.reset()
                stateMachine.startCapture()
                stateMachine.onValidationPass()
                stateMachine.onAnalysisReady()
                stateMachine.onMoveSelected()
            }
            else -> {
                stateMachine.onInputBlocked("HOLD — cannot execute from state ${stateMachine.state}")
                return ExecuteResult.Held("HOLD — cannot execute from state ${stateMachine.state}")
            }
        }
        val dispatch = executor.dispatch(gesture)
        return when (dispatch) {
            is InputDispatchResult.Dispatched -> {
                val hash = Board.fromVision(vision.board).contentHash()
                stateMachine.onInputExecuted()
                ExecuteResult.Executed(dispatch.gesture, hash, move)
            }
            is InputDispatchResult.Failed -> {
                val reason = "HOLD — input dispatch failed: ${dispatch.reason}"
                stateMachine.onInputBlocked(reason)
                ExecuteResult.Held(reason)
            }
        }
    }

    /**
     * Feedback loop: after animation wait + new screenshot/vision, verify board changed.
     * Success → CAPTURE (next move allowed). Failure / invalid / unknown → STOP.
     */
    fun verifyAfterInput(
        beforeBoardHash: Long,
        afterVision: VisionResult,
    ): FeedbackResult {
        if (stateMachine.state == BotLoopState.WAIT_FOR_BOARD) {
            stateMachine.onBoardReadyForVerify()
        }
        return when (val outcome = feedbackVerifier.verify(beforeBoardHash, afterVision)) {
            is InputFeedbackVerifier.VerifyOutcome.BoardChanged -> {
                if (stateMachine.state == BotLoopState.VERIFY_RESULT) {
                    stateMachine.onVerifySuccess()
                } else {
                    // Direct call from tests: mark success by returning to CAPTURE-ready IDLE.
                    stateMachine.reset()
                    stateMachine.startCapture()
                }
                FeedbackResult.Success(outcome.beforeHash, outcome.afterHash)
            }
            is InputFeedbackVerifier.VerifyOutcome.Hold -> {
                stateMachine.hold(outcome.reason)
                FeedbackResult.Held(outcome.reason)
            }
            is InputFeedbackVerifier.VerifyOutcome.Stop -> {
                applyStopToStateMachine(outcome.reason)
                FeedbackResult.Stopped(outcome.reason)
            }
        }
    }

    /**
     * Record a repeated verify failure for fail-safe STOP after
     * [InputThresholds.MAX_VERIFY_FAILURES].
     */
    fun recordRepeatedVerifyFailure(reason: String): FeedbackResult {
        val t = stateMachine.onVerifyFailed(reason)
        return if (t.outcome == BotLoopOutcome.STOP) {
            FeedbackResult.Stopped(t.reason)
        } else {
            FeedbackResult.Held(t.reason)
        }
    }

    private fun applyStopToStateMachine(reason: String) {
        when {
            reason.contains("unknown", ignoreCase = true) ||
                reason.contains("uncertain", ignoreCase = true) ||
                reason.contains("fail-safe", ignoreCase = true) ->
                stateMachine.onUnknownOrUncertain(reason)
            reason.contains("invalid new frame", ignoreCase = true) ->
                stateMachine.onInvalidNewFrame(reason)
            else -> stateMachine.onVerifyFailed(reason)
        }
    }

    companion object {
        const val HOLD_INPUT_DISABLED =
            "HOLD — input disabled (default; enable InputEnableSwitch explicitly)"
        const val HOLD_INPUT_CHANNEL_NOT_READY =
            "HOLD — input channel not ready (AccessibilityService / shell)"
        const val HOLD_VISION_BLOCKED = "HOLD — Decision AI blocked"
        const val HOLD_TOO_MANY_UNKNOWN = "HOLD — unknownCount above limit"
        const val HOLD_NO_LEGAL_MOVE = "HOLD — no valid MoveEvaluation"
        const val HOLD_MOVE_UNCERTAIN = "HOLD — move uncertain (fail-safe)"
        const val HOLD_GRID_INVALID = "HOLD — grid geometry invalid for coord mapping"
    }
}
