package com.match3vision.analyzer.input

import com.match3vision.analyzer.evaluation.MoveEvaluation
import com.match3vision.analyzer.moves.MoveAnalysisEngine
import com.match3vision.analyzer.vision.VisionResult

/**
 * Wires MoveAnalysisEngine (read-only) → AutomaticInputEngine → feedback verify.
 * Input remains OFF unless [InputEnableSwitch] is explicitly enabled.
 * Vision / Move Analysis V1 behavior unchanged when input is disabled.
 */
class InputLoopController(
    private val moveAnalysis: MoveAnalysisEngine = MoveAnalysisEngine(),
    private val inputEngine: AutomaticInputEngine = AutomaticInputEngine(),
    private val animationWaitMs: Long = InputThresholds.ANIMATION_WAIT_MS,
) {
    data class CycleResult(
        val state: BotLoopState,
        val outcome: BotLoopOutcome,
        val reason: String,
        val executed: AutomaticInputEngine.ExecuteResult? = null,
        val feedback: AutomaticInputEngine.FeedbackResult? = null,
        val topMove: MoveEvaluation? = null,
        val animationWaitMs: Long = 0L,
    )

    fun inputEngine(): AutomaticInputEngine = inputEngine
    fun isInputEnabled(): Boolean = inputEngine.isInputEnabled()

    /**
     * One cycle: CAPTURE(assumed done) → VALIDATE → ANALYZE → SELECT → maybe EXECUTE.
     * Does **not** wait/sleep; returns [animationWaitMs] so the caller can wait
     * before [completeFeedback].
     */
    fun runAnalyzeAndMaybeInput(vision: VisionResult): CycleResult {
        val sm = inputEngine.stateMachine()
        if (sm.state == BotLoopState.STOP) {
            return CycleResult(sm.state, BotLoopOutcome.STOP, sm.lastReason)
        }
        if (sm.state == BotLoopState.IDLE || sm.state == BotLoopState.HOLD ||
            sm.state == BotLoopState.CAPTURE
        ) {
            if (sm.state != BotLoopState.CAPTURE) sm.startCapture()
        }

        if (!vision.validation.isPass) {
            val t = sm.onValidationHold(
                (vision.validation as? com.match3vision.analyzer.vision.ValidationResult.Hold)
                    ?.reason ?: AutomaticInputEngine.HOLD_VISION_BLOCKED,
            )
            return CycleResult(t.to, t.outcome, t.reason)
        }
        sm.onValidationPass()

        val analysis = moveAnalysis.analyze(vision)
        if (analysis.blocked) {
            val t = sm.onNoLegalMove(analysis.holdReason ?: MoveAnalysisEngine.HOLD_BLOCKED)
            return CycleResult(t.to, t.outcome, t.reason)
        }
        sm.onAnalysisReady()
        val top = analysis.top5.firstOrNull()
        if (top == null) {
            val t = sm.onNoLegalMove(AutomaticInputEngine.HOLD_NO_LEGAL_MOVE)
            return CycleResult(t.to, t.outcome, t.reason, topMove = null)
        }

        if (!inputEngine.isInputEnabled()) {
            val t = sm.onInputBlocked(AutomaticInputEngine.HOLD_INPUT_DISABLED)
            return CycleResult(
                t.to, t.outcome, t.reason,
                topMove = top,
            )
        }

        val exec = inputEngine.tryExecute(vision, top)
        return when (exec) {
            is AutomaticInputEngine.ExecuteResult.Executed ->
                CycleResult(
                    state = sm.state,
                    outcome = BotLoopOutcome.CONTINUE,
                    reason = "input executed — wait ${animationWaitMs}ms then verify",
                    executed = exec,
                    topMove = top,
                    animationWaitMs = animationWaitMs,
                )
            is AutomaticInputEngine.ExecuteResult.Held ->
                CycleResult(sm.state, BotLoopOutcome.HOLD, exec.reason, executed = exec, topMove = top)
            is AutomaticInputEngine.ExecuteResult.Stopped ->
                CycleResult(sm.state, BotLoopOutcome.STOP, exec.reason, executed = exec, topMove = top)
        }
    }

    /** After animation wait + new vision frame. */
    fun completeFeedback(
        beforeBoardHash: Long,
        afterVision: VisionResult,
    ): CycleResult {
        val fb = inputEngine.verifyAfterInput(beforeBoardHash, afterVision)
        val sm = inputEngine.stateMachine()
        return when (fb) {
            is AutomaticInputEngine.FeedbackResult.Success ->
                CycleResult(
                    state = sm.state,
                    outcome = BotLoopOutcome.CONTINUE,
                    reason = "board changed — next move allowed",
                    feedback = fb,
                )
            is AutomaticInputEngine.FeedbackResult.Held ->
                CycleResult(sm.state, BotLoopOutcome.HOLD, fb.reason, feedback = fb)
            is AutomaticInputEngine.FeedbackResult.Stopped ->
                CycleResult(sm.state, BotLoopOutcome.STOP, fb.reason, feedback = fb)
        }
    }
}
