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

    /** Bounded phone test. Independent of continuous INDÍTÁS. */
    val fiveMove: FiveMoveSession = FiveMoveSession()

    @Volatile
    var consecutiveUnconfirmed: Int = 0
        private set

    /**
     * Set when continuous mode reaches [MAX_CONSECUTIVE_UNCONFIRMED].
     * INDÍTÁS, EGY LÉPÉS, and [resetForNewSession] do not clear it.
     * A new process starts unlatched. Process death is the only clear.
     */
    @Volatile
    var unconfirmedCapLatched: Boolean = false
        private set

    /** A continuous dispatch is waiting for [completeFeedback]. Another cycle cannot dispatch. */
    @Volatile
    private var awaitingFeedback: Boolean = false

    /**
     * Diagnostic capture. The loop may analyze and export.
     * [runCycleIfActive] does not dispatch while this is true.
     */
    @Volatile
    var analysisOnly: Boolean = false
        private set

    /**
     * True only when analysis-only was latched because accessibility was off.
     * A later connection clears [analysisOnly] without enabling the input switch.
     */
    @Volatile
    var analysisOnlyBecauseA11yOff: Boolean = false
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
        if (unconfirmedCapLatched) {
            enableSwitch.setEnabled(false)
            lastReason = "HOLD — MOVE UNCONFIRMED cap latched; start cannot bypass it"
            return false
        }
        if (analysisOnly) {
            enableSwitch.setEnabled(false)
            lastReason = "analysis-only until STOP — no touch"
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
        if (!CoordinateSelfCheck.allowsContinuousStart()) {
            enableSwitch.setEnabled(false)
            if (mode == Mode.RUNNING) {
                mode = Mode.PAUSED
            }
            lastReason = "INDÍTÁS refused — coordinate self-check is " +
                "${CoordinateSelfCheck.statusLabel()} (alignment NOT proven)"
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
        analysisOnly = false
        singleMove.resetIdle()
        enableSwitch.setEnabled(true)
        mode = Mode.RUNNING
        lastReason = "fut — felismerés→lépés→húzás"
        // Drop stale STOP (e.g. prior ACCESSIBILITY: DISCONNECTED) once we are healthy RUNNING.
        AutoPlayTrace.clearLastStop()
        AutoPlayTrace.log("MODE RUNNING", "input ENABLED a11y=CONNECTED")
        return true
    }

    /**
     * Diagnostic INDÍTÁS. Capture and overlay must be ready.
     * Accessibility and the coordinate self-check are not required.
     * Input stays disabled. This does not prove alignment.
     */
    fun onDiagnosticStart(
        captureReady: Boolean = true,
        overlayReady: Boolean = true,
        a11yConnected: Boolean = false,
    ): Boolean {
        if (mode == Mode.STOPPED) {
            lastReason = "leállítva — új Indítás kell az alkalmazásban"
            return false
        }
        if (unconfirmedCapLatched) {
            enableSwitch.setEnabled(false)
            lastReason = "HOLD — MOVE UNCONFIRMED cap latched; start cannot bypass it"
            return false
        }
        if (!captureReady || !overlayReady) {
            enableSwitch.setEnabled(false)
            lastReason = if (!captureReady) "CAPTURE: OFF" else "overlay not ready"
            return false
        }
        val sm = inputLoop.inputEngine().stateMachine()
        if (sm.state == BotLoopState.STOP || sm.state == BotLoopState.HOLD) {
            sm.reset()
        }
        runStyle = RunStyle.CONTINUOUS
        // Latch analysis-only only when accessibility is off. A connected service
        // still keeps the input switch off, so continuous play does not start.
        analysisOnly = !a11yConnected
        analysisOnlyBecauseA11yOff = !a11yConnected
        singleMove.resetIdle()
        enableSwitch.setEnabled(false)
        mode = Mode.RUNNING
        lastReason = if (a11yConnected) {
            "elemzés — kisegítő be, előbb TESZT ÉRINTÉS"
        } else {
            "diagnosztika — elemzés és export, nincs érintés"
        }
        AutoPlayTrace.clearLastStop()
        AutoPlayTrace.log("MODE DIAGNOSTIC", "input DISABLED a11y=$a11yConnected")
        return true
    }

    /**
     * Accessibility connected after an a11y-off latch.
     * Clears analysis-only and leaves the input switch disabled.
     * Does not call [onStartRequested] and does not dispatch.
     */
    fun clearAnalysisOnlyWhenAccessibilityConnects(connected: Boolean) {
        if (!connected || !analysisOnly || !analysisOnlyBecauseA11yOff) return
        analysisOnly = false
        analysisOnlyBecauseA11yOff = false
        enableSwitch.setEnabled(false)
        lastReason = "kisegítő be — előbb TESZT ÉRINTÉS kalibráció kell"
    }

    /**
     * Accessibility dropped while the loop was running.
     * Stay RUNNING so capture, analysis, and export continue.
     * Do not pause, and do not leave input enabled.
     */
    fun enterAnalysisOnly(reason: String, becauseA11yOff: Boolean = false) {
        if (mode == Mode.STOPPED) return
        enableSwitch.setEnabled(false)
        analysisOnly = true
        if (becauseA11yOff) analysisOnlyBecauseA11yOff = true
        if (mode != Mode.RUNNING) {
            mode = Mode.RUNNING
        }
        lastReason = reason
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
        if (analysisOnly) {
            enableSwitch.setEnabled(false)
            lastReason = "analysis-only until STOP — no touch"
            return false
        }
        if (mode == Mode.RUNNING && runStyle == RunStyle.CONTINUOUS) {
            lastReason = "continuous loop is running — pause before arming one move"
            return false
        }
        if (unconfirmedCapLatched) {
            lastReason = "EGY LÉPÉS refused — MOVE UNCONFIRMED cap is latched"
            return false
        }
        if (!CoordinateSelfCheck.allowsSingleMoveArm()) {
            lastReason = "EGY LÉPÉS refused — coordinate self-check is " +
                "${CoordinateSelfCheck.statusLabel()} (alignment NOT proven). " +
                "Run TESZT ÉRINTÉS and record it."
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
        if (fiveMove.isActive) {
            fiveMove.abort("STOP pressed", System.currentTimeMillis())
        }
        enableSwitch.setEnabled(false)
        val sm = inputLoop.inputEngine().stateMachine()
        if (sm.state != BotLoopState.STOP) {
            sm.stop("STOP — $reason")
        }
        mode = Mode.STOPPED
        analysisOnly = false
        analysisOnlyBecauseA11yOff = false
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
        if (unconfirmedCapLatched) {
            enableSwitch.setEnabled(false)
            mode = Mode.PAUSED
            lastReason = "HOLD — MOVE UNCONFIRMED cap latched; no further dispatch"
            return null
        }
        if (awaitingFeedback) {
            lastReason = "HOLD — awaiting verification of the previous dispatch; no second dispatch"
            return null
        }
        if (analysisOnly) {
            enableSwitch.setEnabled(false)
            lastReason = "diagnostic analysis only — no touch"
            return InputLoopController.CycleResult(
                state = inputLoop.inputEngine().stateMachine().state,
                outcome = BotLoopOutcome.HOLD,
                reason = "diagnostic analysis only — no touch",
                verifyStatus = VerificationPolicy.PENDING,
                gestureStatus = "NOT CREATED",
                inputBlockReason = "diagnostic analysis only",
            )
        }
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
        if (runStyle == RunStyle.CONTINUOUS &&
            cycle.executed is AutomaticInputEngine.ExecuteResult.Executed
        ) {
            awaitingFeedback = true
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
        awaitingFeedback = false
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
                unconfirmedCapLatched = true
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

    /**
     * The owner pressed 5 LÉPÉS TESZT while the loop was STOPPED.
     * This is the only way back from STOPPED, and only because that press
     * happened. Input stays off. A missing accessibility service, capture,
     * or overlay leaves the loop STOPPED and tells them to press INDÍTÁS.
     */
    fun explicitFiveMoveRestart(
        a11yConnected: Boolean,
        captureReady: Boolean,
        overlayReady: Boolean,
    ): String? {
        if (mode != Mode.STOPPED) return null
        if (unconfirmedCapLatched) {
            enableSwitch.setEnabled(false)
            lastReason = FiveMoveArm.CAP
            return FiveMoveArm.CAP
        }
        if (!a11yConnected || !captureReady || !overlayReady) {
            lastReason = FiveMoveArm.PRESS_START
            return FiveMoveArm.PRESS_START
        }
        mode = Mode.IDLE
        fiveMove.clear()
        enableSwitch.setEnabled(false)
        analysisOnly = false
        analysisOnlyBecauseA11yOff = false
        lastReason = "10 LÉPÉS — újraindítás a gombnyomásra"
        return null
    }

    /**
     * INDÍTÁS when a saved or fresh calibration is already valid.
     * Starts the analysis loop with the input switch off, so the 10-move
     * session can arm in the same press. Does not enable continuous play.
     */
    fun prepareScoredSession(
        a11yConnected: Boolean,
        captureReady: Boolean,
        overlayReady: Boolean,
    ): Boolean {
        if (mode == Mode.STOPPED) {
            val block = explicitFiveMoveRestart(a11yConnected, captureReady, overlayReady)
            if (block != null) {
                lastReason = block
                return false
            }
        }
        if (unconfirmedCapLatched) {
            enableSwitch.setEnabled(false)
            lastReason = FiveMoveArm.CAP
            return false
        }
        if (!a11yConnected || !captureReady || !overlayReady) {
            enableSwitch.setEnabled(false)
            lastReason = FiveMoveArm.PRESS_START
            return false
        }
        val sm = inputLoop.inputEngine().stateMachine()
        if (sm.state == BotLoopState.STOP || sm.state == BotLoopState.HOLD) {
            sm.reset()
        }
        enableSwitch.setEnabled(false)
        analysisOnly = false
        analysisOnlyBecauseA11yOff = false
        mode = Mode.RUNNING
        lastReason = "játék — 10 lépés, pontozott lépésválasztás"
        AutoPlayTrace.clearLastStop()
        AutoPlayTrace.log("MODE SCORED", "input DISABLED until each 10-move gesture")
        return true
    }

    /**
     * Reasons that do not depend on one camera frame.
     * A missing board is not one of them: the bubble waits for a passing frame.
     */
    fun fiveMoveRefusal(
        selfCheckThisSession: Boolean,
        a11yConnected: Boolean,
    ): String? {
        if (mode == Mode.STOPPED) return FiveMoveArm.STOPPED
        if (!a11yConnected) return FiveMoveArm.NEED_A11Y
        if (!selfCheckThisSession) return FiveMoveArm.NEED_CALIBRATION
        if (mode != Mode.RUNNING) return FiveMoveArm.NEED_START
        if (enableSwitch.isEnabled()) return FiveMoveArm.CONTINUOUS
        if (unconfirmedCapLatched) return FiveMoveArm.CAP
        if (fiveMove.isActive) return FiveMoveArm.ALREADY
        return null
    }

    /**
     * Arm the 5-move test from an analysis-only loop.
     * Does not enable continuous play and does not enable the input switch.
     */
    fun armFiveMoveTest(
        nowMs: Long,
        selfCheckThisSession: Boolean,
        a11yConnected: Boolean = true,
        clockStartMs: Long = nowMs,
    ): Boolean {
        val refusal = fiveMoveRefusal(selfCheckThisSession, a11yConnected)
        if (refusal != null) {
            enableSwitch.setEnabled(false)
            lastReason = refusal
            return false
        }
        if (!fiveMove.arm(clockStartMs)) {
            lastReason = FiveMoveArm.ALREADY
            return false
        }
        enableSwitch.setEnabled(false)
        lastReason = fiveMove.label()
        return true
    }

    /**
     * One authorized gesture for the 5-move test.
     * Refuses a missing permit. Leaves the switch disabled.
     * [analysisOnly] stays true, so [runCycleIfActive] still does not dispatch.
     */
    fun dispatchFiveMoveOnce(
        vision: VisionResult,
        context: RuntimeCycleContext?,
        permit: FiveMoveSession.Permit,
    ): InputLoopController.CycleResult? {
        if (mode != Mode.RUNNING || (!analysisOnly && enableSwitch.isEnabled())) {
            enableSwitch.setEnabled(false)
            lastReason = FiveMoveArm.CONTINUOUS
            return null
        }
        if (fiveMove.phase != FiveMoveSession.Phase.RUNNING || !fiveMove.consumePermit(permit)) {
            enableSwitch.setEnabled(false)
            lastReason = "10 LÉPÉS refused — dispatch permit was not issued"
            return null
        }
        if (unconfirmedCapLatched) {
            enableSwitch.setEnabled(false)
            fiveMove.abort("STOP — MOVE UNCONFIRMED cap latched", System.currentTimeMillis())
            lastReason = fiveMove.stopReason
            return null
        }
        inputLoop.inputEngine().stateMachine().reset()
        enableSwitch.setEnabled(true)
        return try {
            val cycle = inputLoop.runAnalyzeAndMaybeInput(vision, context)
            lastReason = cycle.reason
            cycle
        } catch (t: Throwable) {
            fiveMove.abort("STOP — exception: ${t.message}", System.currentTimeMillis())
            lastReason = fiveMove.stopReason
            null
        } finally {
            enableSwitch.setEnabled(false)
        }
    }

    /**
     * The 5-move test finished or aborted. Input is off and the loop mode is
     * STOPPED. Capture is left running so the caller can still share frames.
     */
    fun finishFiveMoveKeepCapture(reason: String) {
        enableSwitch.setEnabled(false)
        if (fiveMove.isActive) {
            fiveMove.abort(reason, System.currentTimeMillis())
        }
        if (mode != Mode.STOPPED) {
            mode = Mode.STOPPED
        }
        analysisOnly = false
        analysisOnlyBecauseA11yOff = false
        lastReason = reason.ifBlank { fiveMove.stopReason }.ifBlank { "10 LÉPÉS TESZT finished" }
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
        val keepLatch = unconfirmedCapLatched
        mode = Mode.IDLE
        moveCount = 0
        holdCount = 0
        runStyle = RunStyle.CONTINUOUS
        analysisOnly = false
        analysisOnlyBecauseA11yOff = false
        singleMove.resetIdle()
        fiveMove.clear()
        awaitingFeedback = false
        AutoPlayTrace.clear()
        if (keepLatch) {
            lastReason = "tétlen — MOVE UNCONFIRMED cap still latched (reset does not clear it)"
            return
        }
        consecutiveUnconfirmed = 0
        lastReason = "tétlen"
    }

    companion object {
        const val STATUS_IDLE = "tétlen"

        /** Continuous mode pauses after this many MOVE UNCONFIRMED results in a row. */
        const val MAX_CONSECUTIVE_UNCONFIRMED = 2
    }
}
