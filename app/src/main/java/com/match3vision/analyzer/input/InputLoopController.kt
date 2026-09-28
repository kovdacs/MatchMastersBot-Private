package com.match3vision.analyzer.input

import com.match3vision.analyzer.evaluation.MoveEvaluation
import com.match3vision.analyzer.moves.MoveAnalysisEngine
import com.match3vision.analyzer.vision.VisionResult

/**
 * Wires MoveAnalysisEngine (read-only) → AutomaticInputEngine → feedback verify.
 * Input remains OFF unless [InputEnableSwitch] is explicitly enabled.
 * Vision / Move Analysis V1 behavior unchanged when input is disabled.
 *
 * Logs: VISION PASS / MOVE SELECTED / INPUT READY / (gesture+dispatch in engine).
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
        val visionGate: String = "—",
        val moveLabel: String = "none",
        val inputReady: Boolean = false,
        val lastDispatch: StartupReadinessGate.LastDispatch = StartupReadinessGate.LastDispatch.NONE,
        /** NONE | SUCCESS | FAILED — board-changed verification. */
        val verifyStatus: String = "NONE",
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
            AutoPlayTrace.markStop(sm.lastReason)
            return CycleResult(sm.state, BotLoopOutcome.STOP, sm.lastReason)
        }
        if (sm.state == BotLoopState.IDLE || sm.state == BotLoopState.HOLD ||
            sm.state == BotLoopState.CAPTURE
        ) {
            if (sm.state != BotLoopState.CAPTURE) sm.startCapture()
        }

        if (!vision.validation.isPass) {
            val reason = (vision.validation as? com.match3vision.analyzer.vision.ValidationResult.Hold)
                ?.reason ?: AutomaticInputEngine.HOLD_VISION_BLOCKED
            AutoPlayTrace.log("VISION HOLD", reason)
            val t = sm.onValidationHold(reason)
            return CycleResult(
                state = t.to,
                outcome = t.outcome,
                reason = t.reason,
                visionGate = "HOLD — $reason",
                moveLabel = "none",
                inputReady = false,
            )
        }
        AutoPlayTrace.log(AutoPlayTrace.TAG_VISION_PASS, "boardConf=${vision.boardConfidence}")
        sm.onValidationPass()

        val analysis = moveAnalysis.analyze(vision)
        if (analysis.blocked) {
            val reason = analysis.holdReason ?: MoveAnalysisEngine.HOLD_BLOCKED
            AutoPlayTrace.log("MOVE none", reason)
            val t = sm.onNoLegalMove(reason)
            return CycleResult(
                t.to, t.outcome, t.reason,
                visionGate = "PASS",
                moveLabel = "none",
            )
        }
        sm.onAnalysisReady()
        val top = analysis.top5.firstOrNull()
        if (top == null) {
            AutoPlayTrace.log("MOVE none", AutomaticInputEngine.HOLD_NO_LEGAL_MOVE)
            val t = sm.onNoLegalMove(AutomaticInputEngine.HOLD_NO_LEGAL_MOVE)
            return CycleResult(
                t.to, t.outcome, t.reason,
                topMove = null,
                visionGate = "PASS",
                moveLabel = "none",
            )
        }

        val moveLabel =
            "${top.move.r1},${top.move.c1}→${top.move.r2},${top.move.c2}"
        AutoPlayTrace.log(AutoPlayTrace.TAG_MOVE_SELECTED, moveLabel)

        val channelReady = inputEngine.executorReady()
        val switchOn = inputEngine.isInputEnabled()
        val inputReady = switchOn && channelReady
        AutoPlayTrace.log(
            AutoPlayTrace.TAG_INPUT_READY,
            if (inputReady) "YES" else "NO (switch=$switchOn channel=$channelReady)",
        )

        if (!switchOn) {
            val t = sm.onInputBlocked(AutomaticInputEngine.HOLD_INPUT_DISABLED)
            AutoPlayTrace.log("HOLD", t.reason)
            return CycleResult(
                t.to, t.outcome, t.reason,
                topMove = top,
                visionGate = "PASS",
                moveLabel = "selected $moveLabel",
                inputReady = false,
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
                    visionGate = "PASS",
                    moveLabel = "selected $moveLabel",
                    inputReady = true,
                    lastDispatch = StartupReadinessGate.LastDispatch.SUCCESS,
                )
            is AutomaticInputEngine.ExecuteResult.Held -> {
                AutoPlayTrace.log("HOLD", exec.reason)
                val dispatch = when {
                    exec.reason.contains("dispatch failed", ignoreCase = true) ->
                        StartupReadinessGate.LastDispatch.FAILED
                    else -> StartupReadinessGate.LastDispatch.NONE
                }
                CycleResult(
                    sm.state, BotLoopOutcome.HOLD, exec.reason,
                    executed = exec,
                    topMove = top,
                    visionGate = "PASS",
                    moveLabel = "selected $moveLabel",
                    inputReady = inputReady,
                    lastDispatch = dispatch,
                )
            }
            is AutomaticInputEngine.ExecuteResult.Stopped -> {
                AutoPlayTrace.markStop(exec.reason)
                CycleResult(
                    sm.state, BotLoopOutcome.STOP, exec.reason,
                    executed = exec,
                    topMove = top,
                    visionGate = "PASS",
                    moveLabel = "selected $moveLabel",
                    inputReady = inputReady,
                    lastDispatch = StartupReadinessGate.LastDispatch.FAILED,
                )
            }
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
            is AutomaticInputEngine.FeedbackResult.Success -> {
                AutoPlayTrace.log("VERIFY", "SUCCESS board changed")
                CycleResult(
                    state = sm.state,
                    outcome = BotLoopOutcome.CONTINUE,
                    reason = "board changed — next move allowed",
                    feedback = fb,
                    verifyStatus = "SUCCESS",
                    lastDispatch = StartupReadinessGate.LastDispatch.SUCCESS,
                )
            }
            is AutomaticInputEngine.FeedbackResult.Held -> {
                AutoPlayTrace.log("VERIFY", "FAILED/HOLD ${fb.reason}")
                CycleResult(
                    sm.state, BotLoopOutcome.HOLD, fb.reason, feedback = fb,
                    verifyStatus = "FAILED",
                    lastDispatch = StartupReadinessGate.LastDispatch.FAILED,
                )
            }
            is AutomaticInputEngine.FeedbackResult.Stopped -> {
                AutoPlayTrace.log("VERIFY", "FAILED ${fb.reason}")
                AutoPlayTrace.markStop(fb.reason)
                CycleResult(
                    sm.state, BotLoopOutcome.STOP, fb.reason, feedback = fb,
                    verifyStatus = "FAILED",
                    lastDispatch = StartupReadinessGate.LastDispatch.FAILED,
                )
            }
        }
    }
}
