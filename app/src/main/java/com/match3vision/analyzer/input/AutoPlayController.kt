package com.match3vision.analyzer.input

import com.match3vision.analyzer.moves.Move
import com.match3vision.analyzer.vision.VisionResult

/**
 * Continuous auto-play loop control for the floating bubble UX.
 *
 * - Main-screen INDÍTÁS (or bubble INDÍTÁS) calls [onStartRequested] to arm RUNNING.
 * - Requires runtime AccessibilityService connected ([StartupReadinessGate]);
 *   settings flag alone does **not** allow RUNNING / input-ready.
 * - [onBubblePause] stops the loop (keeps session; no gestures).
 * - [onBubbleStop] disables input and marks STOPPED (caller removes bubble / capture).
 * - Reuses [InputLoopController] + [AutomaticInputEngine] gates (PASS/HOLD unchanged).
 * - Failsafe engine STOP → mode PAUSED (user must tap INDÍTÁS again after reset).
 */
class AutoPlayController(
    private val enableSwitch: InputEnableSwitch = InputEnableSwitch.disabledByDefault(),
    private val inputLoop: InputLoopController = InputLoopController(
        inputEngine = AutomaticInputEngine(enableSwitch = enableSwitch),
    ),
) {
    enum class RunStyle { CONTINUOUS, SINGLE_MOVE }

    enum class Mode {
        /** Bubble may be visible; loop not running; input disabled. */
        IDLE,
        /** User / main INDÍTÁS — continuous recognize→move→verify. */
        RUNNING,
        /** User tapped SZÜNET or failsafe — no cycles; input disabled. */
        PAUSED,
        /** User tapped STOP — terminal until new session. */
        STOPPED,
    }

    @Volatile
    var mode: Mode = Mode.IDLE
        private set

    @Volatile
    var lastReason: String = "tétlen"
        private set

    @Volatile
    var moveCount: Int = 0
        private set

    @Volatile
    var holdCount: Int = 0
        private set

    @Volatile
    var runStyle: RunStyle = RunStyle.CONTINUOUS
        private set

    val singleMove: SingleMoveMachine = SingleMoveMachine()

    @Volatile
    var consecutiveUnconfirmed: Int = 0
        private set

    fun inputLoop(): InputLoopController = inputLoop
    fun enableSwitch(): InputEnableSwitch = enableSwitch
    fun isLoopActive(): Boolean = mode == Mode.RUNNING
    fun isSessionAlive(): Boolean = mode != Mode.STOPPED

    /**
     * Main-screen or bubble INDÍTÁS / START.
     *
     * @param a11yConnected runtime [MatchMastersAccessibilityService.isConnected]
     *   (default true only for JVM unit tests that inject a ready executor).
     * @param captureReady MediaProjection ready
     * @param overlayReady overlay permission ready
     * @param settingsEnabled informational; never alone grants RUNNING
     */
    fun onStartRequested(
        a11yConnected: Boolean = true,
        captureReady: Boolean = true,
        overlayReady: Boolean = true,
        settingsEnabled: Boolean = false,
    ): Boolean {
        if (mode == Mode.STOPPED) {
            lastReason = "leállítva — új Indítás kell az alkalmazásban"
            return false
        }
        val gate = StartupReadinessGate.evaluate(
            runtimeConnected = a11yConnected,
            settingsEnabled = settingsEnabled,
            captureReady = captureReady,
            overlayReady = overlayReady,
            inputSwitchEnabled = false, // not yet; we enable only after gate passes
        )
        if (!gate.canEnterRunning) {
            enableSwitch.setEnabled(false)
            // Stay IDLE/PAUSED — never silent HOLD as RUNNING.
            if (mode == Mode.RUNNING) {
                mode = Mode.PAUSED
            }
            lastReason = gate.blockReason ?: "ACCESSIBILITY: DISCONNECTED"
            AutoPlayTrace.markStop(lastReason)
            return false
        }
        if (runStyle == RunStyle.SINGLE_MOVE &&
            singleMove.productionDispatches > 0 &&
            singleMove.phase != SingleMoveMachine.Phase.STOPPED &&
            singleMove.phase != SingleMoveMachine.Phase.IDLE &&
            singleMove.phase != SingleMoveMachine.Phase.PAUSED
        ) {
            lastReason = "single-move protocol in progress — not starting continuous"
            return false
        }
        val sm = inputLoop.inputEngine().stateMachine()
        if (sm.state == BotLoopState.STOP || sm.state == BotLoopState.HOLD) {
            sm.reset()
        }
        runStyle = RunStyle.CONTINUOUS
        singleMove.resetIdle()
        consecutiveUnconfirmed = 0
        enableSwitch.setEnabled(true)
        mode = Mode.RUNNING
        lastReason = "fut — felismerés→lépés→húzás"
        // Drop stale STOP (e.g. prior ACCESSIBILITY: DISCONNECTED) once we are healthy RUNNING.
        AutoPlayTrace.clearLastStop()
        AutoPlayTrace.log("MODE RUNNING", "input ENABLED a11y=CONNECTED")
        return true
    }

    /** Bubble INDÍTÁS alias — same gate as main-screen start. */
    fun onBubbleStart(
        a11yConnected: Boolean = true,
        captureReady: Boolean = true,
        overlayReady: Boolean = true,
        settingsEnabled: Boolean = false,
    ): Boolean = onStartRequested(
        a11yConnected = a11yConnected,
        captureReady = captureReady,
        overlayReady = overlayReady,
        settingsEnabled = settingsEnabled,
    )

    /**
     * Explicit ONE MOVE arm. Uses the same readiness gate as continuous start.
     * One successful return permits exactly one later production dispatch.
     */
    fun armSingleMove(
        a11yConnected: Boolean = true,
        captureReady: Boolean = true,
        overlayReady: Boolean = true,
        settingsEnabled: Boolean = false,
    ): Boolean {
        if (mode == Mode.STOPPED) {
            lastReason = "leállítva — új Indítás kell az alkalmazásban"
            return false
        }
        if (mode == Mode.RUNNING && runStyle == RunStyle.CONTINUOUS) {
            lastReason = "continuous loop is running — pause before arming one move"
            return false
        }
        val gate = StartupReadinessGate.evaluate(
            runtimeConnected = a11yConnected,
            settingsEnabled = settingsEnabled,
            captureReady = captureReady,
            overlayReady = overlayReady,
            inputSwitchEnabled = false,
        )
        if (!gate.canEnterRunning) {
            enableSwitch.setEnabled(false)
            if (mode == Mode.RUNNING) mode = Mode.PAUSED
            lastReason = gate.blockReason ?: "ACCESSIBILITY: DISCONNECTED"
            return false
        }
        if (!singleMove.arm()) {
            lastReason = "single-move arm refused from ${singleMove.label()}"
            return false
        }
        val sm = inputLoop.inputEngine().stateMachine()
        if (sm.state == BotLoopState.STOP || sm.state == BotLoopState.HOLD) {
            sm.reset()
        }
        runStyle = RunStyle.SINGLE_MOVE
        consecutiveUnconfirmed = 0
        enableSwitch.setEnabled(true)
        mode = Mode.RUNNING
        lastReason = "ARMED — exactly one production move"
        AutoPlayTrace.log("MODE SINGLE", "ARMED input ENABLED")
        return true
    }

    /** Bubble SZÜNET / PAUSE — stop loop; keep bubble. */
    fun onBubblePause() {
        if (mode == Mode.STOPPED) return
        enableSwitch.setEnabled(false)
        mode = Mode.PAUSED
        lastReason = "szünet"
    }

    /** Bubble STOP — disable input; terminal. Caller stops capture + removes bubble. */
    fun onBubbleStop(reason: String = "felhasználó STOP") {
        enableSwitch.setEnabled(false)
        val sm = inputLoop.inputEngine().stateMachine()
        if (sm.state != BotLoopState.STOP) {
            sm.stop("STOP — $reason")
        }
        mode = Mode.STOPPED
        lastReason = "leállítva"
        AutoPlayTrace.markStop(reason)
    }

    /** Engine failsafe STOP while running → pause (do not remove bubble). */
    fun onFailsafePause(reason: String) {
        if (mode == Mode.STOPPED) return
        enableSwitch.setEnabled(false)
        mode = Mode.PAUSED
        if (runStyle == RunStyle.SINGLE_MOVE) {
            if (singleMove.productionDispatches > 0) {
                singleMove.finishStopped()
            } else {
                singleMove.resetIdle()
            }
        }
        lastReason = "szünet (biztonság): $reason"
        AutoPlayTrace.markStop(lastReason)
    }

    /**
     * CaptureService gone or projection stopped while the loop was active.
     * Pauses. Does not dispatch.
     */
    fun onCaptureLost(reason: String = "CAPTURE: OFF (service stopped mid-run)") {
        if (mode != Mode.RUNNING) {
            lastReason = reason
            return
        }
        onFailsafePause(reason)
    }

    /**
     * One analyze→maybe-input cycle. Returns null when loop is not RUNNING
     * (IDLE / PAUSED / STOPPED) — never auto-executes before START.
     *
     * [context] is the live observation (frame age, capture, a11y, bounds).
     * Null keeps the previous unit-test path (executor readiness only).
     */
    fun runCycleIfActive(
        vision: VisionResult,
        context: RuntimeCycleContext? = null,
    ): InputLoopController.CycleResult? {
        if (mode != Mode.RUNNING) return null
        if (context != null && !context.a11yConnected) {
            onFailsafePause("ACCESSIBILITY: DISCONNECTED (mid-run)")
            return InputLoopController.CycleResult(
                state = inputLoop.inputEngine().stateMachine().state,
                outcome = BotLoopOutcome.STOP,
                reason = "ACCESSIBILITY: DISCONNECTED (mid-run)",
                verifyStatus = VerificationPolicy.PENDING,
                gestureStatus = "NOT CREATED",
                inputBlockReason = "ACCESSIBILITY: DISCONNECTED",
            )
        }
        if (context != null && !context.captureOn) {
            onCaptureLost("CAPTURE: OFF (service stopped mid-run)")
            return InputLoopController.CycleResult(
                state = inputLoop.inputEngine().stateMachine().state,
                outcome = BotLoopOutcome.STOP,
                reason = "CAPTURE: OFF (service stopped mid-run)",
                verifyStatus = VerificationPolicy.PENDING,
                gestureStatus = "NOT CREATED",
                inputBlockReason = "CAPTURE: OFF",
            )
        }
        if (!enableSwitch.isEnabled()) {
            lastReason = "bevitel ki — várakozás INDÍTÁS-ra"
            AutoPlayTrace.log(AutoPlayTrace.TAG_STOP_REASON, lastReason)
            return null
        }
        if (runStyle == RunStyle.SINGLE_MOVE) {
            singleMove.onObserve()
            if (!singleMove.allowsProductionDispatch()) {
                lastReason = "single-move PAUSE — production dispatch not permitted " +
                    "(${singleMove.label()} count=${singleMove.productionDispatches})"
                return null
            }
        }
        val sm = inputLoop.inputEngine().stateMachine()
        if (sm.state == BotLoopState.STOP) {
            onFailsafePause(sm.lastReason)
            return InputLoopController.CycleResult(
                state = sm.state,
                outcome = BotLoopOutcome.STOP,
                reason = sm.lastReason,
            )
        }
        val cycle = inputLoop.runAnalyzeAndMaybeInput(vision, context)
        lastReason = cycle.reason
        if (runStyle == RunStyle.SINGLE_MOVE &&
            cycle.executed is AutomaticInputEngine.ExecuteResult.Executed
        ) {
            if (!singleMove.tryProductionDispatch()) {
                enableSwitch.setEnabled(false)
                mode = Mode.PAUSED
                lastReason = "single-move refused a second production dispatch"
                return cycle.copy(
                    outcome = BotLoopOutcome.HOLD,
                    reason = lastReason,
                )
            }
            enableSwitch.setEnabled(false)
            mode = Mode.PAUSED
            lastReason = "ONE MOVE dispatched — PAUSE (no second production move)"
        }
        when (cycle.outcome) {
            BotLoopOutcome.CONTINUE -> {
                if (cycle.executed is AutomaticInputEngine.ExecuteResult.Executed) {
                    moveCount += 1
                }
            }
            BotLoopOutcome.HOLD -> {
                holdCount += 1
                AutoPlayTrace.log("HOLD", cycle.reason)
            }
            BotLoopOutcome.STOP -> onFailsafePause(cycle.reason)
        }
        return cycle
    }

    /** Post-swipe feedback. Only meaningful while session alive. */
    fun completeFeedback(
        beforeBoardHash: Long,
        afterVision: VisionResult,
        verify: VerifyObservation? = null,
        beforeVision: VisionResult? = null,
        attemptedMove: Move? = null,
    ): InputLoopController.CycleResult? {
        if (mode == Mode.STOPPED) return null
        val verifyingSingle = runStyle == RunStyle.SINGLE_MOVE &&
            singleMove.phase == SingleMoveMachine.Phase.ONE_MOVE
        if (verifyingSingle) singleMove.beginVerify()
        val fb = inputLoop.completeFeedback(
            beforeBoardHash,
            afterVision,
            verify,
            beforeVision,
            attemptedMove,
        )
        if (verifyingSingle) singleMove.onVerified()
        var result = fb
        if (result.verifyStatus == VerificationPolicy.BOARD_CHANGED_UNCONFIRMED &&
            runStyle == RunStyle.CONTINUOUS
        ) {
            consecutiveUnconfirmed += 1
            if (consecutiveUnconfirmed >= MAX_CONSECUTIVE_UNCONFIRMED) {
                onFailsafePause(
                    "HOLD — consecutive MOVE UNCONFIRMED capped at $MAX_CONSECUTIVE_UNCONFIRMED",
                )
                result = result.copy(
                    outcome = BotLoopOutcome.HOLD,
                    reason = "HOLD — consecutive MOVE UNCONFIRMED capped at " +
                        "$MAX_CONSECUTIVE_UNCONFIRMED; ${result.reason}",
                )
            }
        } else if (result.verifyStatus != VerificationPolicy.BOARD_CHANGED_UNCONFIRMED) {
            consecutiveUnconfirmed = 0
        }
        lastReason = result.reason
        if (result.outcome == BotLoopOutcome.STOP) {
            onFailsafePause(result.reason)
        }
        return result
    }

    /** After the single-move verify bundle is exported. Does not dispatch. */
    fun finishSingleMoveAfterExport() {
        if (runStyle != RunStyle.SINGLE_MOVE) return
        singleMove.finishStopped()
        enableSwitch.setEnabled(false)
        if (mode != Mode.STOPPED) mode = Mode.PAUSED
        lastReason = "STOPPED — single-move protocol finished after export. A new arm is required."
    }

    fun resetForNewSession() {
        enableSwitch.setEnabled(false)
        inputLoop.inputEngine().stateMachine().reset()
        mode = Mode.IDLE
        lastReason = "tétlen"
        moveCount = 0
        holdCount = 0
        runStyle = RunStyle.CONTINUOUS
        consecutiveUnconfirmed = 0
        singleMove.resetIdle()
        AutoPlayTrace.clear()
    }

    companion object {
        const val STATUS_IDLE = "tétlen"

        /** Continuous mode pauses after this many MOVE UNCONFIRMED results in a row. */
        const val MAX_CONSECUTIVE_UNCONFIRMED = 2
    }
}
