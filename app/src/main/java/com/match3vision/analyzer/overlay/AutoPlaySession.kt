package com.match3vision.analyzer.overlay

import com.match3vision.analyzer.capture.AnalysisFrameGate
import com.match3vision.analyzer.input.AutoPlayController
import com.match3vision.analyzer.input.DiagnosticHistoryStore
import com.match3vision.analyzer.input.CaptureOverlayTrace
import com.match3vision.analyzer.input.AutoPlayTrace
import com.match3vision.analyzer.input.AutomaticInputEngine
import com.match3vision.analyzer.input.AutomaticTouchTest
import com.match3vision.analyzer.input.InputEnableSwitch
import com.match3vision.analyzer.input.InputLoopController
import com.match3vision.analyzer.input.ProductionInstall
import com.match3vision.analyzer.input.ProductionLiveReaders
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
        /** Board-changed verify: NONE | SUCCESS | FAILED */
        val verifyStatus: String = "NONE",
        val stopReason: String? = null,
        /** First audit-chain blocker (Hungarian / English mixed, machine-readable). */
        val firstBlock: String? = null,
        // Vision HOLD detail (Hungarian UI — never imply useful play while HOLD-only)
        val unknownCount: Int = -1,
        val gridConfidence: Float = -1f,
        val boardConfidence: Float = -1f,
        val boardDetection: String = "—",
        /** Explicit board ROI string e.g. LTRB(l,t,r,b). */
        val boardRoi: String = "—",
        val frameSequence: String = "—",
        val frameAgeMs: Long = -1L,
        val captureStatus: String = "OFF",
        val moveCount: Int = 0,
        /** Heartbeat tick so UI proves the loop is alive (no silent freeze). */
        val heartbeatMs: Long = 0L,
        /** CREATED / NOT CREATED from the last cycle, not a constant. */
        val gestureStatus: String = "NOT CREATED",
        val frameTimestampMs: Long = -1L,
        val frameWidth: Int = 0,
        val frameHeight: Int = 0,
        /** FRESH / STALE / NONE measured from frame age. */
        val frameFreshness: String = "NONE",
        /** Candidates from MoveAnalysis. -1 = not measured this tick. */
        val moveCandidates: Int = -1,
        val inputBlockReason: String? = null,
        val simulated: Boolean = false,
        val gestureAttemptFailed: Boolean = false,
        val coordinateBlocked: Boolean = false,
        /** Explicit vision pass bit when the caller has a VisionResult. Null → string sniff. */
        val visionPassFlag: Boolean? = null,
        /** Explicit frame-present bit. Null → string sniff of [frame]. */
        val hasFrameFlag: Boolean? = null,
    ) {
        fun bubbleLines(compact: Boolean = true): String {
            val status = LivePipelineStatus(
                mode = mode,
                phase = phase,
                frame = frame,
                boardRoi = boardRoi.ifBlank { boardDetection },
                gridConf = gridConfidence,
                boardConf = boardConfidence,
                unknownCount = unknownCount,
                visionGate = vision,
                moveCount = moveCount,
                selectedMove = move,
                a11y = accessibility,
                inputReady = inputReady,
                lastDispatch = lastDispatch,
                verifyStatus = verifyStatus,
                frameSequence = frameSequence,
                frameAgeMs = frameAgeMs,
                captureStatus = captureStatus,
                firstBlock = firstBlock,
                gestureStatus = gestureStatus,
                frameTimestampMs = frameTimestampMs,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                frameFreshness = frameFreshness,
                moveCandidates = moveCandidates,
                inputBlockReason = inputBlockReason,
                simulated = simulated,
                gestureAttemptFailed = gestureAttemptFailed,
                coordinateBlocked = coordinateBlocked,
            )
            val body = status.bubbleLines(compact = compact)
            return if (!stopReason.isNullOrBlank() && firstBlock != stopReason) {
                body + "\nSTOP: $stopReason"
            } else {
                body
            }
        }
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
        executor = ProductionInstall.accessibilityExecutor(
            liveProbe = ProductionLiveReaders.probe(enableSwitch),
        ),
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
        boardRoi: String? = null,
        frameSequence: String? = null,
        frameAgeMs: Long? = null,
        captureStatus: String? = null,
        verifyStatus: String? = null,
        frameSequenceAllow: Boolean? = null,
        heartbeatMs: Long? = null,
        cycleReason: String? = null,
        gestureStatus: String? = null,
        frameTimestampMs: Long? = null,
        frameWidth: Int? = null,
        frameHeight: Int? = null,
        frameFreshness: String? = null,
        moveCandidates: Int? = null,
        inputBlockReason: String? = null,
        simulated: Boolean? = null,
        gestureAttemptFailed: Boolean? = null,
        coordinateBlocked: Boolean? = null,
        visionPassFlag: Boolean? = null,
        hasFrameFlag: Boolean? = null,
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
            val nextRoi = boardRoi
                ?: boardDetection
                ?: cur.diagnostics.boardRoi
            noteFrameSizeRetention(
                incomingWidth = frameWidth,
                incomingHeight = frameHeight,
                previousWidth = cur.diagnostics.frameWidth,
                previousHeight = cur.diagnostics.frameHeight,
            )
            val diag = rebuildDiagnostics(
                base = cur.diagnostics.copy(
                    frame = frameReceived?.let { if (it) "received" else "no frame" }
                        ?: cur.diagnostics.frame,
                    vision = visionText ?: cur.diagnostics.vision,
                    move = moveText ?: cur.diagnostics.move,
                    lastDispatch = dispatchLabel,
                    verifyStatus = verifyStatus ?: cur.diagnostics.verifyStatus,
                    stopReason = nextStop,
                    accessibility = if (connected) "CONNECTED" else "DISCONNECTED",
                    phase = phase ?: cur.diagnostics.phase,
                    unknownCount = unknownCount ?: cur.diagnostics.unknownCount,
                    gridConfidence = gridConfidence ?: cur.diagnostics.gridConfidence,
                    boardConfidence = boardConfidence ?: cur.diagnostics.boardConfidence,
                    boardDetection = boardDetection ?: cur.diagnostics.boardDetection,
                    boardRoi = nextRoi,
                    frameSequence = frameSequence ?: cur.diagnostics.frameSequence,
                    frameAgeMs = frameAgeMs ?: cur.diagnostics.frameAgeMs,
                    captureStatus = captureStatus ?: cur.diagnostics.captureStatus,
                    heartbeatMs = heartbeatMs ?: System.currentTimeMillis(),
                    gestureStatus = gestureStatus ?: cur.diagnostics.gestureStatus,
                    frameTimestampMs = frameTimestampMs ?: cur.diagnostics.frameTimestampMs,
                    frameWidth = retainPositive(frameWidth, cur.diagnostics.frameWidth),
                    frameHeight = retainPositive(frameHeight, cur.diagnostics.frameHeight),
                    frameFreshness = frameFreshness ?: cur.diagnostics.frameFreshness,
                    moveCandidates = moveCandidates ?: cur.diagnostics.moveCandidates,
                    inputBlockReason = when (inputBlockReason) {
                        null -> cur.diagnostics.inputBlockReason
                        "" -> null
                        else -> inputBlockReason
                    },
                    simulated = simulated ?: cur.diagnostics.simulated,
                    gestureAttemptFailed = gestureAttemptFailed ?: cur.diagnostics.gestureAttemptFailed,
                    coordinateBlocked = coordinateBlocked ?: cur.diagnostics.coordinateBlocked,
                    visionPassFlag = visionPassFlag ?: cur.diagnostics.visionPassFlag,
                    hasFrameFlag = hasFrameFlag ?: cur.diagnostics.hasFrameFlag,
                ),
                a11yConnected = connected,
                captureOn = cur.captureReady,
                overlayReady = cur.overlayReady,
                frameSequenceAllow = frameSequenceAllow,
                cycleReason = cycleReason ?: nextStop,
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

    /** A 0×0 update after pause must not erase a frame that was already measured. */
    private fun retainPositive(incoming: Int?, previous: Int): Int {
        if (incoming == null) return previous
        if (incoming > 0) return incoming
        return if (previous > 0) previous else incoming
    }

    /**
     * Null sizes do not flip the flag. A dropped non-positive size sets it.
     * A positive size that is stored clears it.
     */
    private fun noteFrameSizeRetention(
        incomingWidth: Int?,
        incomingHeight: Int?,
        previousWidth: Int,
        previousHeight: Int,
    ) {
        if (incomingWidth == null && incomingHeight == null) return
        val dropped = (incomingWidth != null && incomingWidth <= 0 && previousWidth > 0) ||
            (incomingHeight != null && incomingHeight <= 0 && previousHeight > 0)
        val applied = (incomingWidth != null && incomingWidth > 0) ||
            (incomingHeight != null && incomingHeight > 0)
        CaptureOverlayTrace.frameSizeRetained = dropped && !applied
    }

    private fun rebuildDiagnostics(
        base: Diagnostics,
        a11yConnected: Boolean,
        captureOn: Boolean,
        overlayReady: Boolean,
        frameSequenceAllow: Boolean? = null,
        cycleReason: String? = null,
    ): Diagnostics {
        val inputEnabled = controller.enableSwitch().isEnabled()
        val gate = StartupReadinessGate.evaluate(
            runtimeConnected = a11yConnected,
            captureReady = captureOn,
            overlayReady = overlayReady,
            inputSwitchEnabled = inputEnabled,
        )
        val modeLabel = when (controller.mode) {
            AutoPlayController.Mode.RUNNING ->
                if (controller.analysisOnly) "ANALYSIS_ONLY" else "RUNNING"
            AutoPlayController.Mode.IDLE -> "IDLE"
            AutoPlayController.Mode.PAUSED -> "PAUSED"
            AutoPlayController.Mode.STOPPED -> "STOPPED"
        }
        val stopResolved = resolveStopReason(
            previous = base.stopReason,
            explicit = null,
            clear = false,
        )
        val visionPass = base.visionPassFlag ?: (
            base.vision.contains("PASS", ignoreCase = true) &&
                !base.vision.contains("HOLD", ignoreCase = true)
            )
        val hasMove = base.move.isNotBlank() &&
            !base.move.equals("none", ignoreCase = true)
        val hasFrame = base.hasFrameFlag ?: (
            base.frame.contains("received", ignoreCase = true) ||
                base.frame.contains("seq=", ignoreCase = true)
            )
        val seqAllow = frameSequenceAllow ?: !base.frameSequence.contains("REJECT", ignoreCase = true)
        val dispatchEnum = when (base.lastDispatch) {
            "SUCCESS" -> StartupReadinessGate.LastDispatch.SUCCESS
            "FAILED" -> StartupReadinessGate.LastDispatch.FAILED
            else -> StartupReadinessGate.LastDispatch.NONE
        }
        val block = LivePipelineStatus.firstBlockingReason(
            mode = controller.mode,
            a11yConnected = a11yConnected,
            captureOn = captureOn,
            hasFrame = hasFrame,
            frameSequenceAllow = seqAllow,
            frameSequenceReason = if (!seqAllow) base.frameSequence else null,
            frameAgeMs = base.frameAgeMs,
            visionPass = visionPass,
            visionHoldReason = if (!visionPass) base.vision else null,
            gridConf = base.gridConfidence,
            boardConf = base.boardConfidence,
            unknownCount = base.unknownCount,
            hasSelectedMove = hasMove,
            inputReady = gate.inputReady,
            lastDispatch = dispatchEnum,
            verifyStatus = base.verifyStatus,
            cycleReason = cycleReason ?: stopResolved,
            coordinateBlocked = base.coordinateBlocked,
            coordinateReason = if (base.coordinateBlocked) base.inputBlockReason else null,
            gestureAttemptFailed = base.gestureAttemptFailed,
            inputBlockReason = when {
                gate.inputReady -> null
                !a11yConnected -> gate.blockReason ?: "ACCESSIBILITY: DISCONNECTED"
                !captureOn -> "CAPTURE: OFF"
                !overlayReady -> "OVERLAY not ready"
                !inputEnabled -> "input switch DISABLED"
                else -> base.inputBlockReason ?: "input channel not ready"
            },
        )
        val blockedReason = if (gate.inputReady) {
            base.inputBlockReason
        } else {
            when {
                !a11yConnected -> gate.blockReason ?: "ACCESSIBILITY: DISCONNECTED"
                !captureOn -> "CAPTURE: OFF"
                !overlayReady -> "OVERLAY not ready"
                !inputEnabled -> "input switch DISABLED"
                else -> base.inputBlockReason ?: "input channel not ready"
            }
        }
        return base.copy(
            mode = modeLabel,
            capture = if (captureOn) "ON" else "OFF",
            input = if (inputEnabled) "ENABLED" else "DISABLED",
            accessibility = if (a11yConnected) "CONNECTED" else "DISCONNECTED",
            inputReady = if (gate.inputReady) "READY" else "BLOCKED",
            stopReason = stopResolved,
            moveCount = controller.moveCount,
            firstBlock = block,
            inputBlockReason = blockedReason,
            boardRoi = if (base.boardRoi != "—" && base.boardRoi.isNotBlank()) {
                base.boardRoi
            } else {
                base.boardDetection
            },
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
        DiagnosticHistoryStore.clearPinForNewSession()
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

    /**
     * STOP has released capture. The bubble must not keep a live FRESH frame
     * or CAPTURE: ON from the last cycle.
     */
    fun publishStoppedCapture() {
        publish(captureReady = false, statusText = "Leállítva")
        updateDiagnostics(
            phase = "LEÁLLÍTVA",
            captureStatus = "OFF",
            frameFreshness = "NONE",
            frameAgeMs = -1L,
            hasFrameFlag = false,
            frameReceived = false,
            stopReason = "leállítva",
            visionPassFlag = false,
            gestureStatus = "NOT CREATED",
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
