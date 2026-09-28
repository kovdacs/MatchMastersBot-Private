package com.match3vision.analyzer.input

import com.match3vision.analyzer.evaluation.MoveEvaluation
import com.match3vision.analyzer.moves.MoveAnalysisEngine
import com.match3vision.analyzer.vision.VisionResult

/**
 * CONTROLLED ON-DEVICE SMOKE TEST — max **one** automatic swipe per session.
 *
 * Flow:
 * ```
 * CAPTURE → VALIDATE → ANALYZE → SELECT → log move → ONE swipe
 *   → WAIT → new frame → Vision → verify board changed
 *   → SUCCESS READY FOR NEXT  |  STOP / HOLD
 * ```
 *
 * Second swipe is blocked until [resetSession]. No continuous / unattended loop.
 * All [AutomaticInputEngine] PASS gates remain required (layered on
 * [InputEnableSwitch] + [SmokeEnableSwitch]).
 * Coordinates come from live [com.match3vision.analyzer.vision.GridGeometry] only.
 */
class OneStepSmokeController(
    private val smokeEnable: SmokeEnableSwitch = SmokeEnableSwitch.disabledByDefault(),
    private val inputEngine: AutomaticInputEngine = AutomaticInputEngine(),
    private val moveAnalysis: MoveAnalysisEngine = MoveAnalysisEngine(),
    private val logger: SmokeTestLogger = SmokeTestLogger(),
    private val animationWaitMs: Long = InputThresholds.ANIMATION_WAIT_MS,
) {
    enum class Phase {
        IDLE,
        RUNNING,
        AWAITING_FEEDBACK,
        SUCCESS_READY_FOR_NEXT,
        HOLD,
        STOP,
    }

    sealed class StepResult {
        data class Held(val reason: String, val phase: Phase) : StepResult()
        data class Stopped(val reason: String, val phase: Phase) : StepResult()
        data class AwaitingFeedback(
            val beforeBoardHash: Long,
            val move: MoveEvaluation,
            val gesture: GestureSpec,
            val animationWaitMs: Long,
            val logSummary: String,
        ) : StepResult()
        data class SuccessReadyForNext(
            val beforeHash: Long,
            val afterHash: Long,
            val move: MoveEvaluation?,
            val logSummary: String,
        ) : StepResult()
    }

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    @Volatile
    var swipeCount: Int = 0
        private set

    @Volatile
    var lastReason: String = "idle"
        private set

    private var pendingBeforeHash: Long? = null
    private var pendingMove: MoveEvaluation? = null
    private var pendingGesture: GestureSpec? = null

    fun smokeEnableSwitch(): SmokeEnableSwitch = smokeEnable
    fun inputEngine(): AutomaticInputEngine = inputEngine
    fun logger(): SmokeTestLogger = logger
    fun isSmokeEnabled(): Boolean = smokeEnable.isEnabled()
    fun animationWaitMs(): Long = animationWaitMs

    /** Explicit user reset — required before a second swipe. */
    fun resetSession() {
        swipeCount = 0
        phase = Phase.IDLE
        lastReason = "session reset"
        pendingBeforeHash = null
        pendingMove = null
        pendingGesture = null
        inputEngine.stateMachine().reset()
        logger.log("RESET — session cleared; swipeCount=0; phase=IDLE")
    }

    /**
     * CAPTURE(vision) → VALIDATE → ANALYZE → SELECT → log → at most ONE swipe.
     * Does not sleep; on swipe returns [StepResult.AwaitingFeedback] so the caller
     * waits [animationWaitMs] then calls [completeWithNewFrame].
     */
    fun runOneStep(vision: VisionResult): StepResult {
        logger.log("——— ONE-STEP SMOKE START ———")
        logger.log("phase=$phase swipeCount=$swipeCount smoke=${smokeEnable.isEnabled()} input=${inputEngine.isInputEnabled()}")

        if (!smokeEnable.isEnabled()) {
            return hold(HOLD_SMOKE_DISABLED)
        }
        if (phase == Phase.STOP) {
            return stop("STOP — session already STOP; reset required")
        }
        if (phase == Phase.SUCCESS_READY_FOR_NEXT || swipeCount >= MAX_SWIPES_PER_SESSION) {
            return hold(HOLD_SECOND_SWIPE_BLOCKED)
        }
        if (phase == Phase.AWAITING_FEEDBACK) {
            return hold("HOLD — awaiting feedback for prior swipe; call completeWithNewFrame")
        }

        phase = Phase.RUNNING
        val sm = inputEngine.stateMachine()
        if (sm.state == BotLoopState.STOP) {
            return stop("STOP — input engine already STOP; reset required")
        }
        if (sm.state == BotLoopState.IDLE || sm.state == BotLoopState.HOLD ||
            sm.state == BotLoopState.CAPTURE
        ) {
            if (sm.state != BotLoopState.CAPTURE) sm.startCapture()
        }

        // VALIDATE
        logger.log("VALIDATE — gate=${vision.validation} boardConf=${"%.4f".format(vision.boardConfidence)} gridConf=${"%.4f".format(vision.gridConfidence)} unk=${vision.unknownCount}")
        if (!vision.validation.isPass) {
            val reason = (vision.validation as? com.match3vision.analyzer.vision.ValidationResult.Hold)
                ?.reason ?: AutomaticInputEngine.HOLD_VISION_BLOCKED
            sm.onValidationHold(reason)
            return hold(reason)
        }
        sm.onValidationPass()
        logger.log("VALIDATE — PASS")

        // ANALYZE
        val analysis = moveAnalysis.analyze(vision)
        logger.log("ANALYZE — blocked=${analysis.blocked} topN=${analysis.top5.size} reason=${analysis.holdReason ?: "ok"}")
        if (analysis.blocked) {
            val reason = analysis.holdReason ?: MoveAnalysisEngine.HOLD_BLOCKED
            sm.onNoLegalMove(reason)
            return hold(reason)
        }
        sm.onAnalysisReady()

        // SELECT
        val top = analysis.top5.firstOrNull()
        if (top == null) {
            sm.onNoLegalMove(AutomaticInputEngine.HOLD_NO_LEGAL_MOVE)
            return hold(AutomaticInputEngine.HOLD_NO_LEGAL_MOVE)
        }
        val fromBox = vision.grid.cellBox(top.move.r1, top.move.c1)
        val toBox = vision.grid.cellBox(top.move.r2, top.move.c2)
        val moveLog =
            "MOVE selected source=(${top.move.r1},${top.move.c1}) center=(${"%.1f".format(fromBox.centerX())},${"%.1f".format(fromBox.centerY())}) " +
                "target=(${top.move.r2},${top.move.c2}) center=(${"%.1f".format(toBox.centerX())},${"%.1f".format(toBox.centerY())}) " +
                "score=${"%.2f".format(top.totalScore)} EV=${"%.2f".format(top.expectedValue)} conf=${"%.3f".format(top.confidence)} " +
                "uncertain=${top.uncertain} why=${top.why}"
        logger.log("SELECT — $moveLog")

        // Layered InputEnableSwitch gate (still required; default DISABLED)
        if (!inputEngine.isInputEnabled()) {
            sm.onInputBlocked(AutomaticInputEngine.HOLD_INPUT_DISABLED)
            return hold(AutomaticInputEngine.HOLD_INPUT_DISABLED)
        }

        // Re-check one-step limit immediately before dispatch (fail-closed).
        if (swipeCount >= MAX_SWIPES_PER_SESSION) {
            return hold(HOLD_SECOND_SWIPE_BLOCKED)
        }

        val exec = inputEngine.tryExecute(vision, top)
        return when (exec) {
            is AutomaticInputEngine.ExecuteResult.Executed -> {
                swipeCount += 1
                pendingBeforeHash = exec.beforeBoardHash
                pendingMove = top
                pendingGesture = exec.gesture
                phase = Phase.AWAITING_FEEDBACK
                lastReason = "swipe dispatched ($swipeCount/$MAX_SWIPES_PER_SESSION)"
                logger.log(
                    "INPUT_DISPATCHED — gesture=(${"%.1f".format(exec.gesture.startX)},${"%.1f".format(exec.gesture.startY)})→" +
                        "(${"%.1f".format(exec.gesture.endX)},${"%.1f".format(exec.gesture.endY)}) " +
                        "dur=${exec.gesture.durationMs}ms beforeHash=${exec.beforeBoardHash} swipeCount=$swipeCount",
                )
                logger.log("WAIT — animationWaitMs=$animationWaitMs then capture new frame")
                StepResult.AwaitingFeedback(
                    beforeBoardHash = exec.beforeBoardHash,
                    move = top,
                    gesture = exec.gesture,
                    animationWaitMs = animationWaitMs,
                    logSummary = moveLog,
                )
            }
            is AutomaticInputEngine.ExecuteResult.Held -> hold(exec.reason)
            is AutomaticInputEngine.ExecuteResult.Stopped -> stop(exec.reason)
        }
    }

    /**
     * After animation wait + new Vision frame: verify board changed.
     * Success → [Phase.SUCCESS_READY_FOR_NEXT] (no second swipe until [resetSession]).
     */
    fun completeWithNewFrame(afterVision: VisionResult): StepResult {
        logger.log(
            "POST_VERIFY — gate=${afterVision.validation} boardConf=${"%.4f".format(afterVision.boardConfidence)} " +
                "gridConf=${"%.4f".format(afterVision.gridConfidence)} unk=${afterVision.unknownCount}",
        )
        if (phase != Phase.AWAITING_FEEDBACK) {
            return hold("HOLD — completeWithNewFrame called in phase=$phase")
        }
        val before = pendingBeforeHash
            ?: return stop("STOP — missing beforeBoardHash (fail-safe)")

        val fb = inputEngine.verifyAfterInput(before, afterVision)
        return when (fb) {
            is AutomaticInputEngine.FeedbackResult.Success -> {
                phase = Phase.SUCCESS_READY_FOR_NEXT
                lastReason = "SUCCESS READY FOR NEXT — board changed; reset for another smoke step"
                logger.log(
                    "BOARD_CHANGED — beforeHash=${fb.beforeHash} afterHash=${fb.afterHash}",
                )
                logger.log("SUCCESS READY FOR NEXT — swipeCount=$swipeCount; second swipe BLOCKED until reset")
                // Freeze further continuous looping: engine SM may be CAPTURE; we stay SUCCESS.
                StepResult.SuccessReadyForNext(
                    beforeHash = fb.beforeHash,
                    afterHash = fb.afterHash,
                    move = pendingMove,
                    logSummary = lastReason,
                )
            }
            is AutomaticInputEngine.FeedbackResult.Held -> {
                logger.log("HOLD — ${fb.reason}")
                hold(fb.reason)
            }
            is AutomaticInputEngine.FeedbackResult.Stopped -> {
                logger.log("STOP / FAILSAFE — ${fb.reason}")
                stop(fb.reason)
            }
        }
    }

    private fun hold(reason: String): StepResult {
        phase = Phase.HOLD
        lastReason = reason
        logger.log("HOLD — $reason")
        return StepResult.Held(reason, phase)
    }

    private fun stop(reason: String): StepResult {
        phase = Phase.STOP
        lastReason = reason
        logger.log("STOP — $reason")
        return StepResult.Stopped(reason, phase)
    }

    companion object {
        const val MAX_SWIPES_PER_SESSION = 1
        const val HOLD_SMOKE_DISABLED =
            "HOLD — smoke disabled (default; enable SmokeEnableSwitch explicitly)"
        const val HOLD_SECOND_SWIPE_BLOCKED =
            "HOLD — one-step smoke already used this session; reset required before another swipe"
    }
}
