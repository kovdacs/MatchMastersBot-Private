package com.match3vision.analyzer.overlay

import com.match3vision.analyzer.capture.AnalysisFrameGate
import com.match3vision.analyzer.input.AccessibilityGestureExecutor
import com.match3vision.analyzer.input.AutoPlayController
import com.match3vision.analyzer.input.AutoPlayTrace
import com.match3vision.analyzer.input.AutomaticInputEngine
import com.match3vision.analyzer.input.AutomaticTouchTest
import com.match3vision.analyzer.input.InputEnableSwitch
import com.match3vision.analyzer.input.InputLoopController
import com.match3vision.analyzer.input.StartupReadinessGate
import com.match3vision.analyzer.orchestration.AnalysisOrchestrator
import com.match3vision.analyzer.vision.VisionFrameAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide auto-play session shared by MainActivity, FloatingBubbleService,
 * and AnalyzerViewModel.
 *
 * Main-screen INDÍTÁS arms RUNNING when a11y is runtime-connected
 * (settings flag alone is not enough). Bubble INDÍTÁS remains as a manual
 * resume/control; it is not required for the first start after main INDÍTÁS.
 */
object AutoPlaySession {

    data class Diagnostics(
        val mode: String = "IDLE",
        val phase: String = "TÉTLEN",
        val capture: String = "OFF",
        val frame: String = "no frame",
        val vision: String = "—",
        val move: String = "none",
        val input: String = "DISABLED",
        val accessibility: String = "DISCONNECTED",
        val inputReady: String = "NO",
        val lastDispatch: String = "NONE",
        val stopReason: String? = null,
        // Vision HOLD detail (Hungarian UI — never imply useful play while HOLD-only)
        val unknownCount: Int = -1,
        val gridConfidence: Float = -1f,
        val boardConfidence: Float = -1f,
        val boardDetection: String = "—",
        val frameSequence: String = "—",
        val frameAgeMs: Long = -1L,
        val captureStatus: String = "OFF",
    ) {
        fun bubbleLines(): String = buildString {
            appendLine("FÁZIS: $phase")
            appendLine("MODE: $mode")
            appendLine("CAPTURE: $capture")
            appendLine("FRAME: $frame")
            appendLine("VISION: $vision")
            if (vision.contains("HOLD", ignoreCase = true) || unknownCount >= 0) {
                appendLine("  unk=$unknownCount grid=${fmt(gridConfidence)} board=${fmt(boardConfidence)}")
                appendLine("  boardDet=$boardDetection")
                appendLine("  frameSeq=$frameSequence ageMs=$frameAgeMs")
                appendLine("  captureStatus=$captureStatus")
            }
            appendLine("MOVE: $move")
            appendLine("INPUT: $input")
            appendLine("ACCESSIBILITY: $accessibility")
            appendLine("INPUT READY: $inputReady")
            append("LAST DISPATCH: $lastDispatch")
            if (!stopReason.isNullOrBlank()) {
                appendLine()
                append("STOP: $stopReason")
            }
        }

        private fun fmt(v: Float): String =
            if (v < 0f) "—" else "%.3f".format(v)
    }

    data class UiSnapshot(
        val mode: AutoPlayController.Mode = AutoPlayController.Mode.IDLE,
        val statusText: String = "Tétlen — nyomd meg az INDÍTÁS-t",
        val moveCount: Int = 0,
        val holdCount: Int = 0,
        val bubbleVisible: Boolean = false,
        /** Runtime a11y connected (not settings flag). */
        val a11yReady: Boolean = false,
        /** Settings.Secure lists the service (informational). */
        val a11ySettingsEnabled: Boolean = false,
        val overlayReady: Boolean = false,
        val captureReady: Boolean = false,
        /** True while loop RUNNING — analyzer UI should stay compact / backgrounded. */
        val compactUi: Boolean = false,
        val diagnostics: Diagnostics = Diagnostics(),
    )

    private val enableSwitch = InputEnableSwitch.disabledByDefault()
    private val inputEngine = AutomaticInputEngine(
        enableSwitch = enableSwitch,
        executor = AccessibilityGestureExecutor(),
    )
    private val inputLoop = InputLoopController(inputEngine = inputEngine)
    val controller: AutoPlayController = AutoPlayController(
        enableSwitch = enableSwitch,
        inputLoop = inputLoop,
    )

    /** Isolated touch test (bubble TESZT ÉRINTÉS) — no Vision. */
    val touchTest: AutomaticTouchTest = AutomaticTouchTest()

    val frameAnalyzer: VisionFrameAnalyzer = VisionFrameAnalyzer()
    val orchestrator: AnalysisOrchestrator = AnalysisOrchestrator()

    /** Shared MediaProjection analysis freeze gate (Activity + bubble). */
    val frameGate: AnalysisFrameGate = AnalysisFrameGate()

    /** Post-gesture NEW/SAME/OLD frame gate. */
    val frameSequenceGate: com.match3vision.analyzer.capture.FrameSequenceGate =
        com.match3vision.analyzer.capture.FrameSequenceGate()

    private val _ui = MutableStateFlow(UiSnapshot())
    val ui: StateFlow<UiSnapshot> = _ui.asStateFlow()

    fun publish(
        statusText: String? = null,
        bubbleVisible: Boolean? = null,
        a11yReady: Boolean? = null,
        a11ySettingsEnabled: Boolean? = null,
        overlayReady: Boolean? = null,
        captureReady: Boolean? = null,
    ) {
        val visible = bubbleVisible ?: _ui.value.bubbleVisible
        frameGate.setBubbleOverlayOnly(visible)
        frameGate.setBubbleLoopRunning(controller.mode == AutoPlayController.Mode.RUNNING)
        _ui.update { cur ->
            val nextA11y = a11yReady ?: cur.a11yReady
            val nextCapture = captureReady ?: cur.captureReady
            val nextOverlay = overlayReady ?: cur.overlayReady
            val nextSettings = a11ySettingsEnabled ?: cur.a11ySettingsEnabled
            cur.copy(
                mode = controller.mode,
                statusText = statusText ?: controller.lastReason,
                moveCount = controller.moveCount,
                holdCount = controller.holdCount,
                bubbleVisible = visible,
                a11yReady = nextA11y,
                a11ySettingsEnabled = nextSettings,
                overlayReady = nextOverlay,
                captureReady = nextCapture,
                compactUi = controller.mode == AutoPlayController.Mode.RUNNING,
                diagnostics = rebuildDiagnostics(
                    base = cur.diagnostics,
                    a11yConnected = nextA11y,
                    captureOn = nextCapture,
                    overlayReady = nextOverlay,
                ),
            )
        }
    }

    fun refreshFromController(statusOverride: String? = null) {
        publish(statusText = statusOverride)
    }

    fun updateDiagnostics(
        frameReceived: Boolean? = null,
        visionText: String? = null,
        moveText: String? = null,
        lastDispatch: StartupReadinessGate.LastDispatch? = null,
        stopReason: String? = null,
        a11yConnected: Boolean? = null,
        /** When true, drop sticky STOP line (successful RUNNING / new session). */
        clearStopReason: Boolean = false,
        phase: String? = null,
        unknownCount: Int? = null,
        gridConfidence: Float? = null,
        boardConfidence: Float? = null,
        boardDetection: String? = null,
        frameSequence: String? = null,
        frameAgeMs: Long? = null,
        captureStatus: String? = null,
    ) {
        if (clearStopReason) {
            AutoPlayTrace.clearLastStop()
        }
        _ui.update { cur ->
            val connected = a11yConnected ?: cur.a11yReady
            val dispatchLabel = when (lastDispatch) {
                StartupReadinessGate.LastDispatch.SUCCESS -> "SUCCESS"
                StartupReadinessGate.LastDispatch.FAILED -> "FAILED"
                StartupReadinessGate.LastDispatch.NONE -> "NONE"
                null -> cur.diagnostics.lastDispatch
            }
            val nextStop = resolveStopReason(
                previous = cur.diagnostics.stopReason,
                explicit = stopReason,
                clear = clearStopReason,
            )
            val diag = rebuildDiagnostics(
                base = cur.diagnostics.copy(
                    frame = frameReceived?.let { if (it) "received" else "no frame" }
                        ?: cur.diagnostics.frame,
                    vision = visionText ?: cur.diagnostics.vision,
                    move = moveText ?: cur.diagnostics.move,
                    lastDispatch = dispatchLabel,
                    stopReason = nextStop,
                    accessibility = if (connected) "CONNECTED" else "DISCONNECTED",
                    phase = phase ?: cur.diagnostics.phase,
                    unknownCount = unknownCount ?: cur.diagnostics.unknownCount,
                    gridConfidence = gridConfidence ?: cur.diagnostics.gridConfidence,
                    boardConfidence = boardConfidence ?: cur.diagnostics.boardConfidence,
                    boardDetection = boardDetection ?: cur.diagnostics.boardDetection,
                    frameSequence = frameSequence ?: cur.diagnostics.frameSequence,
                    frameAgeMs = frameAgeMs ?: cur.diagnostics.frameAgeMs,
                    captureStatus = captureStatus ?: cur.diagnostics.captureStatus,
                ),
                a11yConnected = connected,
                captureOn = cur.captureReady,
                overlayReady = cur.overlayReady,
            )
            cur.copy(
                a11yReady = connected,
                diagnostics = diag,
                mode = controller.mode,
                moveCount = controller.moveCount,
                holdCount = controller.holdCount,
                compactUi = controller.mode == AutoPlayController.Mode.RUNNING,
            )
        }
    }

    private fun rebuildDiagnostics(
        base: Diagnostics,
        a11yConnected: Boolean,
        captureOn: Boolean,
        overlayReady: Boolean,
    ): Diagnostics {
        val inputEnabled = controller.enableSwitch().isEnabled()
        val gate = StartupReadinessGate.evaluate(
            runtimeConnected = a11yConnected,
            captureReady = captureOn,
            overlayReady = overlayReady,
            inputSwitchEnabled = inputEnabled,
        )
        val modeLabel = when (controller.mode) {
            AutoPlayController.Mode.IDLE -> "IDLE"
            AutoPlayController.Mode.RUNNING -> "RUNNING"
            AutoPlayController.Mode.PAUSED -> "PAUSED"
            AutoPlayController.Mode.STOPPED -> "STOPPED"
        }
        return base.copy(
            mode = modeLabel,
            capture = if (captureOn) "ON" else "OFF",
            input = if (inputEnabled) "ENABLED" else "DISABLED",
            accessibility = if (a11yConnected) "CONNECTED" else "DISCONNECTED",
            inputReady = if (gate.inputReady) "YES" else "NO",
            // Never re-infect a cleared STOP while RUNNING (stale ACCESSIBILITY: DISCONNECTED).
            stopReason = resolveStopReason(
                previous = base.stopReason,
                explicit = null,
                clear = false,
            ),
        )
    }

    /** @see DiagnosticsStopDisplay.resolve */
    internal fun resolveStopReason(
        previous: String?,
        explicit: String?,
        clear: Boolean,
    ): String? = DiagnosticsStopDisplay.resolve(
        mode = controller.mode,
        previous = previous,
        explicit = explicit,
        clear = clear,
        traceLast = AutoPlayTrace.lastStopReason,
    )

    fun beginNewSession() {
        controller.resetForNewSession()
        frameGate.setBubbleLoopRunning(false)
        frameSequenceGate.reset()
        AutoPlayTrace.clearLastStop()
        _ui.update { cur ->
            cur.copy(diagnostics = cur.diagnostics.copy(stopReason = null))
        }
        publish(
            statusText = "Kész — fő INDÍTÁS indítja a kört (buborék kontroll)",
            bubbleVisible = true,
        )
    }

    fun endSession() {
        if (controller.mode != AutoPlayController.Mode.STOPPED) {
            controller.onBubbleStop("session end")
        }
        frameGate.setBubbleLoopRunning(false)
        frameGate.setBubbleOverlayOnly(false)
        frameSequenceGate.reset()
        publish(statusText = "Leállítva", bubbleVisible = false)
    }

    /** Sync gate flags after START / PAUSE / STOP. */
    fun syncFrameGateFromMode() {
        frameGate.setBubbleLoopRunning(controller.mode == AutoPlayController.Mode.RUNNING)
        frameGate.setBubbleOverlayOnly(_ui.value.bubbleVisible)
        _ui.update {
            it.copy(
                mode = controller.mode,
                compactUi = controller.mode == AutoPlayController.Mode.RUNNING,
                diagnostics = rebuildDiagnostics(
                    base = it.diagnostics,
                    a11yConnected = it.a11yReady,
                    captureOn = it.captureReady,
                    overlayReady = it.overlayReady,
                ),
            )
        }
    }
}
