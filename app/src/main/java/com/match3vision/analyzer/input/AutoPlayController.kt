package com.match3vision.analyzer.input

import com.match3vision.analyzer.vision.VisionResult

/**
 * Continuous auto-play loop control for the floating bubble UX.
 *
 * - Does **not** auto-start: [onBubbleStart] required before any cycle runs.
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
    enum class Mode {
        /** Bubble may be visible; loop not running; input disabled. */
        IDLE,
        /** User tapped bubble INDÍTÁS — continuous recognize→move→verify. */
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

    fun inputLoop(): InputLoopController = inputLoop
    fun enableSwitch(): InputEnableSwitch = enableSwitch
    fun isLoopActive(): Boolean = mode == Mode.RUNNING
    fun isSessionAlive(): Boolean = mode != Mode.STOPPED

    /**
     * Bubble INDÍTÁS / START. Enables input and arms the continuous loop.
     * No-op / false when already STOPPED (caller must create a new session).
     */
    fun onBubbleStart(): Boolean {
        if (mode == Mode.STOPPED) {
            lastReason = "leállítva — új Indítás kell az alkalmazásban"
            return false
        }
        val sm = inputLoop.inputEngine().stateMachine()
        if (sm.state == BotLoopState.STOP || sm.state == BotLoopState.HOLD) {
            sm.reset()
        }
        enableSwitch.setEnabled(true)
        mode = Mode.RUNNING
        lastReason = "fut — felismerés→lépés→húzás"
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
    }

    /** Engine failsafe STOP while running → pause (do not remove bubble). */
    fun onFailsafePause(reason: String) {
        if (mode == Mode.STOPPED) return
        enableSwitch.setEnabled(false)
        mode = Mode.PAUSED
        lastReason = "szünet (biztonság): $reason"
    }

    /**
     * One analyze→maybe-input cycle. Returns null when loop is not RUNNING
     * (IDLE / PAUSED / STOPPED) — never auto-executes before bubble START.
     */
    fun runCycleIfActive(vision: VisionResult): InputLoopController.CycleResult? {
        if (mode != Mode.RUNNING) return null
        if (!enableSwitch.isEnabled()) {
            lastReason = "bevitel ki — várakozás INDÍTÁS-ra"
            return null
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
        val cycle = inputLoop.runAnalyzeAndMaybeInput(vision)
        lastReason = cycle.reason
        when (cycle.outcome) {
            BotLoopOutcome.CONTINUE -> {
                if (cycle.executed is AutomaticInputEngine.ExecuteResult.Executed) {
                    moveCount += 1
                }
            }
            BotLoopOutcome.HOLD -> holdCount += 1
            BotLoopOutcome.STOP -> onFailsafePause(cycle.reason)
        }
        return cycle
    }

    /** Post-swipe feedback. Only meaningful while session alive. */
    fun completeFeedback(
        beforeBoardHash: Long,
        afterVision: VisionResult,
    ): InputLoopController.CycleResult? {
        if (mode == Mode.STOPPED) return null
        val fb = inputLoop.completeFeedback(beforeBoardHash, afterVision)
        lastReason = fb.reason
        if (fb.outcome == BotLoopOutcome.STOP) {
            onFailsafePause(fb.reason)
        }
        return fb
    }

    fun resetForNewSession() {
        enableSwitch.setEnabled(false)
        inputLoop.inputEngine().stateMachine().reset()
        mode = Mode.IDLE
        lastReason = "tétlen"
        moveCount = 0
        holdCount = 0
    }

    companion object {
        const val STATUS_IDLE = "tétlen"
    }
}
