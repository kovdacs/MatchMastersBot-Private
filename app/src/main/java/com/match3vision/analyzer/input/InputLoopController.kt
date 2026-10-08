package com.match3vision.analyzer.input

import com.match3vision.analyzer.capture.FrameSequenceGate
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
        /**
         * PENDING until a new fresh frame is judged.
         * SUCCESS only from [VerificationPolicy], never from dispatch itself.
         */
        val verifyStatus: String = VerificationPolicy.PENDING,
        val gestureStatus: String = "NOT CREATED",
        val moveCandidates: Int = 0,
        val inputBlockReason: String? = null,
        val simulated: Boolean = false,
        val gestureAttemptFailed: Boolean = false,
        val coordinateBlocked: Boolean = false,
    )

    fun inputEngine(): AutomaticInputEngine = inputEngine
    fun isInputEnabled(): Boolean = inputEngine.isInputEnabled()

    /**
     * One cycle: CAPTURE(assumed done) → VALIDATE → ANALYZE → SELECT → maybe EXECUTE.
     * Does **not** wait/sleep; returns [animationWaitMs] so the caller can wait
     * before [completeFeedback].
     */
    fun runAnalyzeAndMaybeInput(
        vision: VisionResult,
        context: RuntimeCycleContext? = null,
    ): CycleResult {
        val sm = inputEngine.stateMachine()
        if (sm.state == BotLoopState.STOP) {
            AutoPlayTrace.markStop(sm.lastReason)
            return CycleResult(
                sm.state, BotLoopOutcome.STOP, sm.lastReason,
                simulated = context?.simulated == true,
            )
        }
        if (sm.state == BotLoopState.IDLE || sm.state == BotLoopState.HOLD ||
            sm.state == BotLoopState.CAPTURE
        ) {
            if (sm.state != BotLoopState.CAPTURE) sm.startCapture()
        }

        // Stale / missing / non-new frames are not a new decision. No MoveAnalysis dispatch.
        if (context != null) {
            val pre = preDecisionBlock(context)
            if (pre != null) {
                AutoPlayTrace.log("FRAME", pre)
                val t = sm.onValidationHold(pre)
                return CycleResult(
                    state = t.to,
                    outcome = t.outcome,
                    reason = pre,
                    visionGate = "HOLD — $pre",
                    moveLabel = "none",
                    inputReady = false,
                    verifyStatus = VerificationPolicy.PENDING,
                    gestureStatus = "NOT CREATED",
                    simulated = context.simulated,
                    inputBlockReason = pre,
                )
            }
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
        val candidateCount = analysis.top5.size
        if (analysis.blocked) {
            val reason = analysis.holdReason ?: MoveAnalysisEngine.HOLD_BLOCKED
            AutoPlayTrace.log("MOVE none", reason)
            val t = sm.onNoLegalMove(reason)
            return CycleResult(
                t.to, t.outcome, t.reason,
                visionGate = "PASS",
                moveLabel = "none",
                moveCandidates = 0,
                simulated = context?.simulated == true,
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
                moveCandidates = 0,
                simulated = context?.simulated == true,
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

        if (!switchOn || (context != null && !context.a11yConnected) || !channelReady) {
            val reason = when {
                !switchOn -> AutomaticInputEngine.HOLD_INPUT_DISABLED
                context != null && !context.a11yConnected -> "ACCESSIBILITY: DISCONNECTED"
                else -> AutomaticInputEngine.HOLD_INPUT_CHANNEL_NOT_READY
            }
            val t = sm.onInputBlocked(reason)
            AutoPlayTrace.log("HOLD", t.reason)
            return CycleResult(
                t.to, t.outcome, t.reason,
                topMove = top,
                visionGate = "PASS",
                moveLabel = "selected $moveLabel",
                inputReady = false,
                moveCandidates = candidateCount,
                inputBlockReason = reason,
                gestureStatus = "NOT CREATED",
                lastDispatch = StartupReadinessGate.LastDispatch.NONE,
                verifyStatus = VerificationPolicy.PENDING,
                simulated = context?.simulated == true,
            )
        }

        val exec = inputEngine.tryExecute(vision, top, context)
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
                    // Dispatch succeeded. Verification is still pending a new frame.
                    verifyStatus = VerificationPolicy.afterDispatch(dispatchSucceeded = true),
                    gestureStatus = "CREATED",
                    moveCandidates = candidateCount,
                    simulated = context?.simulated == true,
                )
            is AutomaticInputEngine.ExecuteResult.Held -> {
                AutoPlayTrace.log("HOLD", exec.reason)
                CycleResult(
                    sm.state, BotLoopOutcome.HOLD, exec.reason,
                    executed = exec,
                    topMove = top,
                    visionGate = "PASS",
                    moveLabel = "selected $moveLabel",
                    inputReady = false,
                    lastDispatch = StartupReadinessGate.LastDispatch.NONE,
                    verifyStatus = VerificationPolicy.PENDING,
                    gestureStatus = "NOT CREATED",
                    moveCandidates = candidateCount,
                    inputBlockReason = exec.reason,
                    simulated = context?.simulated == true,
                )
            }
            is AutomaticInputEngine.ExecuteResult.Stopped -> {
                AutoPlayTrace.markStop(exec.reason)
                val notCreated = exec.reason.contains("NOT CREATED", ignoreCase = true)
                val dispatchFailed = exec.reason.contains("dispatch failed", ignoreCase = true)
                val coordBlocked = exec.reason.contains("off-screen", ignoreCase = true) ||
                    exec.reason.contains("bounds unknown", ignoreCase = true) ||
                    exec.reason.contains("non-finite", ignoreCase = true) ||
                    exec.reason.contains("frame/screen", ignoreCase = true)
                CycleResult(
                    sm.state, BotLoopOutcome.STOP, exec.reason,
                    executed = exec,
                    topMove = top,
                    visionGate = "PASS",
                    moveLabel = "selected $moveLabel",
                    inputReady = inputReady,
                    lastDispatch = if (dispatchFailed) {
                        StartupReadinessGate.LastDispatch.FAILED
                    } else {
                        StartupReadinessGate.LastDispatch.NONE
                    },
                    verifyStatus = VerificationPolicy.PENDING,
                    gestureStatus = if (notCreated) "NOT CREATED" else "CREATED",
                    gestureAttemptFailed = notCreated,
                    coordinateBlocked = coordBlocked,
                    moveCandidates = candidateCount,
                    inputBlockReason = exec.reason,
                    simulated = context?.simulated == true,
                )
            }
        }
    }

    /**
     * Frame problems that must not become a move decision.
     * Null means the frame may be analyzed.
     */
    private fun preDecisionBlock(context: RuntimeCycleContext): String? {
        if (!context.captureOn) return "CAPTURE: OFF"
        if (!context.hasFrame) return "FRAME: no frame"
        val seq = context.frameSequenceDecision
        if (seq != null && !seq.allow) return seq.reason
        if (context.frameAgeMs > GestureFailSafe.MAX_FRAME_AGE_MS) {
            return FrameSequenceGate.HOLD_STALE_FRAME +
                " age=${context.frameAgeMs}ms > ${GestureFailSafe.MAX_FRAME_AGE_MS}ms"
        }
        return null
    }

    /**
     * After animation wait + a **new** vision frame.
     *
     * When [verify] is present, a non-new or stale frame is FAILED without
     * reading the board — dispatch success cannot become VERIFY SUCCESS.
     * When [verify] is null (existing unit callers that already supply a fresh
     * vision), the verifier result is the decision.
     */
    fun completeFeedback(
        beforeBoardHash: Long,
        afterVision: VisionResult,
        verify: VerifyObservation? = null,
    ): CycleResult {
        val frameTooEarly = verify != null && !verify.frameIsAfterDispatch()
        val boardSame = verify?.boardUnchanged == true
        if (verify != null && (!verify.newFrameAccepted || !verify.frameFresh || frameTooEarly || boardSame)) {
            val reason = verify.reason.ifBlank {
                when {
                    boardSame ->
                        "VERIFY FAILED — board unchanged (identical to pre-move; no SUCCESS)"
                    frameTooEarly ->
                        "VERIFY FAILED — frame is not later than dispatch completion (no SUCCESS)"
                    else ->
                        "VERIFY FAILED — stale or non-new frame cannot decide (no SUCCESS)"
                }
            }
            val sm = inputEngine.stateMachine()
            if (sm.state != BotLoopState.STOP) {
                sm.stop("STOP — $reason")
            }
            AutoPlayTrace.log("VERIFY", "FAILED $reason")
            AutoPlayTrace.markStop(reason)
            return CycleResult(
                state = sm.state,
                outcome = BotLoopOutcome.STOP,
                reason = reason,
                verifyStatus = VerificationPolicy.decide(
                    newFrameAccepted = verify.newFrameAccepted,
                    frameFresh = verify.frameFresh,
                    frameVerifiable = false,
                    boardChanged = false,
                ),
                // The gesture was already dispatched; this failure is verification.
                lastDispatch = StartupReadinessGate.LastDispatch.SUCCESS,
                gestureStatus = "CREATED",
            )
        }
        val fb = inputEngine.verifyAfterInput(beforeBoardHash, afterVision)
        val sm = inputEngine.stateMachine()
        return when (fb) {
            is AutomaticInputEngine.FeedbackResult.Success -> {
                val label = VerificationPolicy.decide(
                    newFrameAccepted = true,
                    frameFresh = verify?.frameFresh ?: true,
                    frameVerifiable = true,
                    boardChanged = true,
                )
                AutoPlayTrace.log("VERIFY", "$label board changed")
                CycleResult(
                    state = sm.state,
                    outcome = if (label == VerificationPolicy.SUCCESS) {
                        BotLoopOutcome.CONTINUE
                    } else {
                        BotLoopOutcome.STOP
                    },
                    reason = if (label == VerificationPolicy.SUCCESS) {
                        "board changed — next move allowed"
                    } else {
                        "VERIFY FAILED — policy refused SUCCESS"
                    },
                    feedback = fb,
                    verifyStatus = label,
                    lastDispatch = StartupReadinessGate.LastDispatch.SUCCESS,
                    gestureStatus = "CREATED",
                )
            }
            is AutomaticInputEngine.FeedbackResult.Held -> {
                AutoPlayTrace.log("VERIFY", "FAILED/HOLD ${fb.reason}")
                CycleResult(
                    sm.state, BotLoopOutcome.HOLD, fb.reason, feedback = fb,
                    verifyStatus = VerificationPolicy.FAILED,
                    lastDispatch = StartupReadinessGate.LastDispatch.SUCCESS,
                    gestureStatus = "CREATED",
                )
            }
            is AutomaticInputEngine.FeedbackResult.Stopped -> {
                AutoPlayTrace.log("VERIFY", "FAILED ${fb.reason}")
                AutoPlayTrace.markStop(fb.reason)
                CycleResult(
                    sm.state, BotLoopOutcome.STOP, fb.reason, feedback = fb,
                    verifyStatus = VerificationPolicy.FAILED,
                    lastDispatch = StartupReadinessGate.LastDispatch.SUCCESS,
                    gestureStatus = "CREATED",
                )
            }
        }
    }
}
