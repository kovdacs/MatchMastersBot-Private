package com.match3vision.analyzer.input

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.evaluation.MoveEvaluation
import com.match3vision.analyzer.vision.VisionResult
import com.match3vision.analyzer.vision.VisionValidator

/**
 * AUTOMATIC INPUT ENGINE V1 — separate from Vision / MoveAnalysis / DecisionEngine.
 *
 * Executes a swipe **only** when all gates pass and [InputEnableSwitch] is explicitly
 * enabled (default DISABLED). Otherwise HOLD — Decision AI blocked, no input.
 *
 * Real gesture path: [AccessibilityGestureExecutor] installed by [ProductionInstall].
 * The default executor is [UninstalledGestureExecutor] — never the test recorder.
 * Never uses hardcoded pvp_board coordinates — [TouchCoordinateMapper] + grid centers.
 */
class AutomaticInputEngine(
    private val enableSwitch: InputEnableSwitch = InputEnableSwitch.disabledByDefault(),
    private val executor: InputGestureExecutor = UninstalledGestureExecutor(),
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
    /** Installed channel. Production must be [AccessibilityGestureExecutor]. */
    fun executor(): InputGestureExecutor = executor
    /** Runtime input channel ready (AccessibilityService connected). */
    fun executorReady(): Boolean = executor.isReady()

    /**
     * Evaluate whether input is allowed. Does not dispatch.
     * Fail-closed: any uncertainty → HOLD.
     */
    fun evaluateGate(
        vision: VisionResult,
        move: MoveEvaluation?,
        a11yConnected: Boolean = true,
        captureOk: Boolean = true,
        hasFrame: Boolean = true,
        frameAgeMs: Long = 0L,
        frameSequenceDecision: com.match3vision.analyzer.capture.FrameSequenceGate.Decision? = null,
    ): GateDecision {
        val fs = GestureFailSafe.evaluate(
            GestureFailSafe.Context(
                vision = vision,
                move = move,
                inputEnabled = enableSwitch.isEnabled(),
                executorReady = executor.isReady(),
                a11yConnected = a11yConnected,
                captureOk = captureOk,
                hasFrame = hasFrame,
                frameAgeMs = frameAgeMs,
                frameSequenceDecision = frameSequenceDecision,
                minMoveConfidence = minMoveConfidence,
            ),
        )
        return GateDecision(fs.allow, fs.reason)
    }

    /**
     * Attempt to execute [move] if gates pass. On failure → HOLD (no input).
     * On success returns pre-board hash for the feedback loop.
     */
    fun tryExecute(
        vision: VisionResult,
        move: MoveEvaluation?,
        context: RuntimeCycleContext? = null,
    ): ExecuteResult {
        // The live accessibility executor must not inherit evaluateGate defaults
        // (a11y connected, fresh frame, age 0) and must not run a simulation context.
        if (ProductionPath.isProductionExecutor(executor)) {
            val refusal = ProductionPath.productionReadinessRefusal(context)
            if (refusal != null) {
                stateMachine.onInputBlocked(refusal)
                AutoPlayTrace.log("HOLD", refusal)
                return ExecuteResult.Held(refusal)
            }
        }
        val gate = if (context == null) {
            evaluateGate(vision, move)
        } else {
            evaluateGate(
                vision = vision,
                move = move,
                a11yConnected = context.a11yConnected,
                captureOk = context.captureOn,
                hasFrame = context.hasFrame,
                frameAgeMs = context.frameAgeMs,
                frameSequenceDecision = context.frameSequenceDecision,
            )
        }
        if (!gate.allow || move == null) {
            stateMachine.onInputBlocked(gate.reason)
            return ExecuteResult.Held(gate.reason)
        }
        val gesture = try {
            coordinateMapper.toGesture(move.move, vision.grid)
        } catch (t: Throwable) {
            // One failed build must not spin. STOP — caller pauses, no second dispatch.
            val reason = "STOP — gesture NOT CREATED: coord conversion failed: ${t.message}"
            stateMachine.stop(reason)
            AutoPlayTrace.log("GESTURE", reason)
            return ExecuteResult.Stopped(reason)
        }
        if (context != null) {
            val space = FrameScreenCoordinatePolicy.assess(
                frameWidth = context.frameWidth,
                frameHeight = context.frameHeight,
                screenWidth = context.screenWidth,
                screenHeight = context.screenHeight,
            )
            if (space.mapping != FrameScreenCoordinatePolicy.Mapping.IDENTITY_FRAME_PIXELS) {
                val reason = "STOP — gesture NOT CREATED: ${space.reason}"
                stateMachine.stop(reason)
                AutoPlayTrace.log("GESTURE", reason)
                return ExecuteResult.Stopped(reason)
            }
            val coord = CoordinateBounds.check(gesture, context.screenWidth, context.screenHeight)
            if (!coord.allow) {
                val reason = "STOP — gesture NOT CREATED: ${coord.reason}"
                stateMachine.stop(reason)
                AutoPlayTrace.log("GESTURE", reason)
                return ExecuteResult.Stopped(reason)
            }
        }
        AutoPlayTrace.log(
            AutoPlayTrace.TAG_GESTURE_CREATED,
            "start=(%.1f,%.1f) end=(%.1f,%.1f) durationMs=%d".format(
                gesture.startX, gesture.startY, gesture.endX, gesture.endY, gesture.durationMs,
            ),
        )
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
        AutoPlayTrace.log(AutoPlayTrace.TAG_DISPATCH_START, gesture.toString())
        val dispatch = if (context != null && executor is AccessibilityGestureExecutor) {
            val permit = DispatchPermit.from(
                context = context,
                vision = vision,
                gesture = gesture,
                inputEnabled = enableSwitch.isEnabled(),
            )
            executor.dispatchChecked(permit)
        } else {
            executor.dispatch(gesture)
        }
        return when (dispatch) {
            is InputDispatchResult.Dispatched -> {
                AutoPlayTrace.log(AutoPlayTrace.TAG_DISPATCH_RESULT, "SUCCESS")
                val hash = Board.fromVision(vision.board).contentHash()
                stateMachine.onInputExecuted()
                ExecuteResult.Executed(dispatch.gesture, hash, move)
            }
            is InputDispatchResult.Failed -> {
                AutoPlayTrace.log(AutoPlayTrace.TAG_DISPATCH_RESULT, "FAILED — ${dispatch.reason}")
                // One failed dispatch does not retry. STOP → controller pauses.
                val reason = "STOP — input dispatch failed (no retry): ${dispatch.reason}"
                stateMachine.stop(reason)
                ExecuteResult.Stopped(reason)
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
