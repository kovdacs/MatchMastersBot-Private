package com.match3vision.analyzer.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.match3vision.analyzer.capture.CaptureFrame
import com.match3vision.analyzer.capture.CaptureService
import com.match3vision.analyzer.capture.ContentRoi
import com.match3vision.analyzer.overlay.AutoPlaySession
import com.match3vision.analyzer.input.AutomaticInputEngine
import com.match3vision.analyzer.input.InputEnableSwitch
import com.match3vision.analyzer.input.OneStepSmokeController
import com.match3vision.analyzer.input.ProductionInstall
import com.match3vision.analyzer.input.SmokeEnableSwitch
import com.match3vision.analyzer.input.SmokeTestLogger
import com.match3vision.analyzer.orchestration.AnalysisOrchestrator
import com.match3vision.analyzer.orchestration.AnalysisUiSnapshot
import com.match3vision.analyzer.vision.VisionFrameAnalyzer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AnalyzerStatus {
    Idle,
    AwaitingPermission,
    Capturing,
    Stopped,
    Error,
}

data class AnalyzerUiState(
    val status: AnalyzerStatus = AnalyzerStatus.Idle,
    val statusMessage: String = "Tétlen",
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val contentRoiText: String = "—",
    val frameCount: Long = 0,
    val lastFrameBitmap: Bitmap? = null,
    val lastContentRoi: ContentRoi? = null,
    val visionStatusText: String = "Látás: —",
    val visionDebugText: String = "",
    val boardGridLabels: List<String> = emptyList(),
    val gateHold: Boolean = false,
    val holdMessage: String? = null,
    val topMovesText: List<String> = emptyList(),
    val whyText: String = "",
    val confidenceText: String = "",
    val riskText: String = "",
    val expectedValueText: String = "",
    val gameStateText: String = "",
    val analysisSnapshot: AnalysisUiSnapshot? = null,
    val subtitle: String = SUBTITLE,
    // Controlled one-step smoke (default DISABLED)
    val inputEnabled: Boolean = false,
    val smokeEnabled: Boolean = false,
    val smokePhase: String = "IDLE",
    val smokeSwipeCount: Int = 0,
    val smokeStatusText: String = "Próba: KI (alapértelmezett)",
    val smokeLogText: String = "",
    val smokeRunning: Boolean = false,
    /** Analysis bitmap freeze status (MediaProjection self-UI guard). */
    val frameGateText: String = "FAGYASZTVA (indítás)",
    val analysisFrameFrozen: Boolean = true,
) {
    companion object {
        const val SUBTITLE = "Auto — egy INDÍTÁS: engedélyek → rögzítés → buborék → auto kör (a11y CONNECTED kell)"
    }
}

/**
 * Binds [CaptureService] frame flow to Compose UI state.
 * Analyze last frame uses the **real** last captured bitmap (no fake image).
 * Decision output is display-only unless the user explicitly enables
 * Input + One-Step Smoke and taps Run (max 1 auto swipe).
 *
 * Must expose a Java-visible `(Application)` constructor for
 * `AndroidViewModelFactory` / `by viewModels()`. Kotlin default params alone
 * only emit the full primary ctor + DefaultConstructorMarker overload — that
 * caused cold-start Instantiation crash on device (white screen ~2s then exit).
 * `@JvmOverloads` restores the Application-only overload factory needs.
 */
class AnalyzerViewModel @JvmOverloads constructor(
    application: Application,
    private val frameAnalyzer: VisionFrameAnalyzer = VisionFrameAnalyzer(),
    private val orchestrator: AnalysisOrchestrator = AnalysisOrchestrator(),
) : AndroidViewModel(application) {

    private val inputEnableSwitch = InputEnableSwitch.disabledByDefault()
    private val smokeEnableSwitch = SmokeEnableSwitch.disabledByDefault()
    private val smokeLogger = SmokeTestLogger(
        file = File(application.filesDir, "smoke_test.log"),
    )
    private val smokeController = OneStepSmokeController(
        smokeEnable = smokeEnableSwitch,
        inputEngine = AutomaticInputEngine(
            enableSwitch = inputEnableSwitch,
            executor = ProductionInstall.accessibilityExecutor(),
        ),
        logger = smokeLogger,
    )
    /** Shared with FloatingBubbleService — bubble FUT always accepts live frames. */
    private val frameGate get() = AutoPlaySession.frameGate

    private val _uiState = MutableStateFlow(AnalyzerUiState())
    val uiState: StateFlow<AnalyzerUiState> = _uiState.asStateFlow()

    private var observeJob: Job? = null
    private var analyzeJob: Job? = null
    private var smokeJob: Job? = null

    init {
        smokeLogger.log("APP_START — InputEnableSwitch=DISABLED SmokeEnableSwitch=DISABLED")
        publishSmokeUi("Próba: KI (alapértelmezett) — kapcsold be a kapcsolókat, majd Futtatás")
    }

    fun onStartRequested() {
        _uiState.update {
            it.copy(
                status = AnalyzerStatus.AwaitingPermission,
                statusMessage = "Képernyőrögzítés engedélyére vár…",
            )
        }
    }

    fun onCapturePermissionDenied() {
        _uiState.update {
            it.copy(
                status = AnalyzerStatus.Error,
                statusMessage = "Képernyőrögzítés engedély elutasítva",
            )
        }
    }

    fun onCaptureServiceStarted() {
        _uiState.update {
            it.copy(
                status = AnalyzerStatus.Capturing,
                statusMessage = "Rögzítés…",
            )
        }
        startObservingFrames()
    }

    fun onCaptureServiceStopped() {
        observeJob?.cancel()
        observeJob = null
        _uiState.update {
            it.copy(
                status = AnalyzerStatus.Stopped,
                statusMessage = "Leállítva",
                lastFrameBitmap = null,
                lastContentRoi = null,
            )
        }
    }

    /**
     * Call from Activity onResume/onPause.
     * When the analyzer UI is foreground, MediaProjection frames are our own panel —
     * freeze the last board frame captured while paused (Match Masters visible).
     */
    fun onAnalyzerUiForeground(foreground: Boolean) {
        frameGate.setAnalyzerUiForeground(foreground)
        smokeLogger.log(
            "FRAME_GATE — analyzerUiForeground=$foreground acceptLive=${frameGate.shouldAcceptLiveFrame()} " +
                "status=${frameGate.statusText()}",
        )
        _uiState.update {
            it.copy(
                frameGateText = frameGate.statusText(),
                analysisFrameFrozen = !frameGate.shouldAcceptLiveFrame(),
            )
        }
    }

    fun setInputEnabled(enabled: Boolean) {
        inputEnableSwitch.setEnabled(enabled)
        smokeLogger.log("UI — InputEnableSwitch=${if (enabled) "ENABLED" else "DISABLED"}")
        publishSmokeUi(
            if (enabled) "Bevitel: BE (kell még Próba + kisegítő + Futtatás)"
            else "Bevitel: KI",
        )
    }

    fun setSmokeEnabled(enabled: Boolean) {
        smokeEnableSwitch.setEnabled(enabled)
        smokeLogger.log("UI — SmokeEnableSwitch=${if (enabled) "ENABLED" else "DISABLED"}")
        publishSmokeUi(
            if (enabled) "Próba: BE (max. 1 húzás; nyomd meg: Futtatás)"
            else "Próba: KI",
        )
    }

    fun resetSmokeSession() {
        smokeController.resetSession()
        publishSmokeUi("Próba: visszaállítva — kész egy húzásra")
    }

    /**
     * Controlled one-step smoke on the last captured frame.
     * Max 1 auto swipe; waits for a new frame then verifies board change.
     * Never fakes BOARD_CHANGED.
     */
    fun runOneStepSmoke() {
        if (smokeJob?.isActive == true) return
        val bitmap = _uiState.value.lastFrameBitmap
        val roi = _uiState.value.lastContentRoi
        if (bitmap == null || bitmap.isRecycled) {
            publishSmokeUi("Próba: nincs képkocka — Indítás + nyisd meg a Match Masters táblát")
            return
        }
        smokeJob = viewModelScope.launch {
            _uiState.update { it.copy(smokeRunning = true) }
            publishSmokeUi("Próba: fut RÖGZÍTÉS→ELLENŐRZÉS→ELEMZÉS→KIVÁLASZTÁS…")
            try {
                val (beforeAnalysis, beforeSnap) = withContext(Dispatchers.Default) {
                    val fa = analyzeBitmap(bitmap, roi)
                    fa to orchestrator.analyzeVisionResult(fa.result)
                }
                applyVisionToUi(beforeAnalysis, beforeSnap)
                val step = withContext(Dispatchers.Default) {
                    smokeController.runOneStep(beforeAnalysis.result)
                }
                when (step) {
                    is OneStepSmokeController.StepResult.Held -> {
                        publishSmokeUi("Próba TARTÁS: ${step.reason}")
                    }
                    is OneStepSmokeController.StepResult.Stopped -> {
                        publishSmokeUi("Próba LEÁLLÍTVA: ${step.reason}")
                    }
                    is OneStepSmokeController.StepResult.SuccessReadyForNext -> {
                        publishSmokeUi("Próba SIKER — KÉSZ A KÖVETKEZŐRE")
                    }
                    is OneStepSmokeController.StepResult.AwaitingFeedback -> {
                        publishSmokeUi(
                            "Próba: húzás elküldve — várakozás ${step.animationWaitMs} ms… " +
                                "(tábla legyen látható: osztott képernyő / PiP)",
                        )
                        // Temporarily accept live frames so post-swipe board can update.
                        // Fullscreen analyzer UI still covers the board — split-screen/PiP required.
                        frameGate.setForceAcceptLive(true)
                        _uiState.update {
                            it.copy(
                                frameGateText = frameGate.statusText(),
                                analysisFrameFrozen = false,
                            )
                        }
                        try {
                        delay(step.animationWaitMs)
                        // Prefer a newer frame than the one we swiped on.
                        val startCount = _uiState.value.frameCount
                        var waited = 0L
                        while (isActive && waited < 2_500L &&
                            _uiState.value.frameCount <= startCount
                        ) {
                            delay(100L)
                            waited += 100L
                        }
                        val afterBmp = _uiState.value.lastFrameBitmap
                        val afterRoi = _uiState.value.lastContentRoi
                        if (afterBmp == null || afterBmp.isRecycled) {
                            publishSmokeUi("Próba LEÁLLÍTVA: nincs húzás utáni képkocka")
                            return@launch
                        }
                        val (afterAnalysis, afterSnap) = withContext(Dispatchers.Default) {
                            val fa = analyzeBitmap(afterBmp, afterRoi)
                            fa to orchestrator.analyzeVisionResult(fa.result)
                        }
                        applyVisionToUi(afterAnalysis, afterSnap)
                        val fb = withContext(Dispatchers.Default) {
                            smokeController.completeWithNewFrame(afterAnalysis.result)
                        }
                        when (fb) {
                            is OneStepSmokeController.StepResult.SuccessReadyForNext ->
                                publishSmokeUi(
                                    "Próba SIKER — KÉSZ A KÖVETKEZŐRE — tábla változott " +
                                        "${fb.beforeHash}→${fb.afterHash}",
                                )
                            is OneStepSmokeController.StepResult.Held ->
                                publishSmokeUi("Próba TARTÁS: ${fb.reason}")
                            is OneStepSmokeController.StepResult.Stopped ->
                                publishSmokeUi("Próba LEÁLLÍTVA: ${fb.reason}")
                            is OneStepSmokeController.StepResult.AwaitingFeedback ->
                                publishSmokeUi("Próba: váratlan várakozó állapot")
                        }
                        } finally {
                            frameGate.setForceAcceptLive(false)
                            _uiState.update {
                                it.copy(
                                    frameGateText = frameGate.statusText(),
                                    analysisFrameFrozen = !frameGate.shouldAcceptLiveFrame(),
                                )
                            }
                        }
                    }
                }
            } finally {
                _uiState.update { it.copy(smokeRunning = false) }
                publishSmokeUi(_uiState.value.smokeStatusText)
            }
        }
    }

    /**
     * Runs full vision (+ orchestrated decision when gate PASS) on the last
     * captured frame. Safe no-op when no frame exists. Never injects input.
     */
    fun analyzeLastFrame() {
        val bitmap = _uiState.value.lastFrameBitmap
        val roi = _uiState.value.lastContentRoi
        if (bitmap == null || bitmap.isRecycled) {
            _uiState.update { it.copy(visionStatusText = "Látás: nincs elemezhető képkocka") }
            return
        }
        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            _uiState.update { it.copy(visionStatusText = "Látás: elemzés…") }
            val analysis = withContext(Dispatchers.Default) {
                val frameAnalysis = analyzeBitmap(bitmap, roi)
                val snap = orchestrator.analyzeVisionResult(frameAnalysis.result)
                frameAnalysis to snap
            }
            val (frameAnalysis, snap) = analysis
            applyVisionToUi(frameAnalysis, snap)
        }
    }

    private fun analyzeBitmap(
        bitmap: Bitmap,
        roi: ContentRoi?,
    ) = frameAnalyzer.analyzePixels(
        pixels = IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        },
        width = bitmap.width,
        height = bitmap.height,
        contentRoi = roi,
    )

    private fun applyVisionToUi(
        frameAnalysis: com.match3vision.analyzer.vision.VisionFrameAnalyzer.FrameAnalysis,
        snap: AnalysisUiSnapshot? = null,
    ) {
        val resolvedSnap = snap ?: orchestrator.analyzeVisionResult(frameAnalysis.result)
        val dbg = frameAnalysis.debugSummary
        val hold = dbg.gate == "HOLD" || resolvedSnap.decisionBlocked
        _uiState.update {
            it.copy(
                visionStatusText = "Látás: ${dbg.gate} · ${dbg.gridMethod} · unk=${dbg.unknownCount} · " +
                    "board=${"%.2f".format(dbg.boardConfidence)} grid=${"%.2f".format(dbg.gridConfidence)}",
                visionDebugText = "ROI ${dbg.roiText} · cells=${dbg.cellLabels.size}" +
                    (dbg.holdReason?.let { r -> " · $r" } ?: "") +
                    (frameAnalysis.result.diagnostics["suspectHint"]?.let { " · $it" } ?: "") +
                    (frameAnalysis.result.diagnostics["occSummary"]?.let { " · occ{$it}" } ?: ""),
                boardGridLabels = dbg.cellLabels,
                gateHold = hold,
                holdMessage = if (hold) {
                    resolvedSnap.holdReason ?: dbg.holdReason ?: "TARTÁS — döntési AI blokkolva"
                } else null,
                topMovesText = if (hold) emptyList() else resolvedSnap.topMovesLines,
                whyText = resolvedSnap.whyText,
                confidenceText = resolvedSnap.confidenceText,
                riskText = resolvedSnap.riskText,
                expectedValueText = resolvedSnap.expectedValueText,
                gameStateText = resolvedSnap.gameStateText,
                analysisSnapshot = resolvedSnap,
            )
        }
    }

    private fun publishSmokeUi(status: String) {
        _uiState.update {
            it.copy(
                inputEnabled = inputEnableSwitch.isEnabled(),
                smokeEnabled = smokeEnableSwitch.isEnabled(),
                smokePhase = smokeController.phase.name,
                smokeSwipeCount = smokeController.swipeCount,
                smokeStatusText = status,
                smokeLogText = smokeLogger.dump(),
            )
        }
    }

    private fun startObservingFrames() {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            var lastSeen: CaptureFrame? = null
            while (isActive) {
                AutoPlaySession.syncFrameGateFromMode()
                val manager = CaptureService.managerOrNull()
                val frame = manager?.latestFrame?.value
                if (frame != null && frame !== lastSeen) {
                    lastSeen = frame
                    publishFrame(frame)
                }
                val capturing = manager?.isCapturing?.value == true
                if (!capturing && _uiState.value.status == AnalyzerStatus.Capturing) {
                    _uiState.update {
                        it.copy(status = AnalyzerStatus.Stopped, statusMessage = "Leállítva")
                    }
                }
                delay(100)
            }
        }
    }

    private fun publishFrame(frame: CaptureFrame) {
        val accept = frameGate.shouldAcceptLiveFrame()
        frameGate.onFrameOffered(accept)
        _uiState.update { state ->
            if (accept) {
                state.copy(
                    status = AnalyzerStatus.Capturing,
                    statusMessage = "Rögzítés ${frame.width}×${frame.height}",
                    frameWidth = frame.width,
                    frameHeight = frame.height,
                    contentRoiText = formatRoi(frame.contentRoi),
                    frameCount = state.frameCount + 1,
                    lastFrameBitmap = frame.bitmap,
                    lastContentRoi = frame.contentRoi,
                    frameGateText = frameGate.statusText(),
                    analysisFrameFrozen = false,
                )
            } else {
                // Keep last board bitmap; still show that capture is flowing.
                state.copy(
                    status = AnalyzerStatus.Capturing,
                    statusMessage = "Rögzítés ${frame.width}×${frame.height} (képkocka fagyasztva)",
                    frameWidth = frame.width,
                    frameHeight = frame.height,
                    contentRoiText = formatRoi(state.lastContentRoi ?: frame.contentRoi),
                    frameCount = state.frameCount + 1,
                    frameGateText = frameGate.statusText(),
                    analysisFrameFrozen = true,
                )
            }
        }
    }

    private fun formatRoi(roi: ContentRoi?): String {
        if (roi == null) return "teljes kép"
        return "LTRB(${roi.left}, ${roi.top}, ${roi.right}, ${roi.bottom}) " +
            "${roi.width()}×${roi.height()}"
    }

    override fun onCleared() {
        observeJob?.cancel()
        analyzeJob?.cancel()
        smokeJob?.cancel()
        super.onCleared()
    }
}
