package com.match3vision.analyzer.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.match3vision.analyzer.capture.CaptureFrame
import com.match3vision.analyzer.capture.CaptureService
import com.match3vision.analyzer.capture.ContentRoi
import com.match3vision.analyzer.input.AccessibilityGestureExecutor
import com.match3vision.analyzer.input.AutomaticInputEngine
import com.match3vision.analyzer.input.InputEnableSwitch
import com.match3vision.analyzer.input.OneStepSmokeController
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
    val statusMessage: String = "Idle",
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val contentRoiText: String = "—",
    val frameCount: Long = 0,
    val lastFrameBitmap: Bitmap? = null,
    val lastContentRoi: ContentRoi? = null,
    val visionStatusText: String = "Vision: —",
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
    val smokeStatusText: String = "Smoke: DISABLED (default)",
    val smokeLogText: String = "",
    val smokeRunning: Boolean = false,
) {
    companion object {
        const val SUBTITLE = "Analyzer + controlled one-step smoke (input DEFAULT DISABLED)"
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
            executor = AccessibilityGestureExecutor(),
        ),
        logger = smokeLogger,
    )

    private val _uiState = MutableStateFlow(AnalyzerUiState())
    val uiState: StateFlow<AnalyzerUiState> = _uiState.asStateFlow()

    private var observeJob: Job? = null
    private var analyzeJob: Job? = null
    private var smokeJob: Job? = null

    init {
        smokeLogger.log("APP_START — InputEnableSwitch=DISABLED SmokeEnableSwitch=DISABLED")
        publishSmokeUi("Smoke: DISABLED (default) — enable switches then Run One-Step")
    }

    fun onStartRequested() {
        _uiState.update {
            it.copy(
                status = AnalyzerStatus.AwaitingPermission,
                statusMessage = "Awaiting MediaProjection permission…",
            )
        }
    }

    fun onCapturePermissionDenied() {
        _uiState.update {
            it.copy(
                status = AnalyzerStatus.Error,
                statusMessage = "Screen capture permission denied",
            )
        }
    }

    fun onCaptureServiceStarted() {
        _uiState.update {
            it.copy(
                status = AnalyzerStatus.Capturing,
                statusMessage = "Capturing…",
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
                statusMessage = "Stopped",
                lastFrameBitmap = null,
                lastContentRoi = null,
            )
        }
    }

    fun setInputEnabled(enabled: Boolean) {
        inputEnableSwitch.setEnabled(enabled)
        smokeLogger.log("UI — InputEnableSwitch=${if (enabled) "ENABLED" else "DISABLED"}")
        publishSmokeUi(
            if (enabled) "Input: ENABLED (still needs Smoke + a11y + Run)"
            else "Input: DISABLED",
        )
    }

    fun setSmokeEnabled(enabled: Boolean) {
        smokeEnableSwitch.setEnabled(enabled)
        smokeLogger.log("UI — SmokeEnableSwitch=${if (enabled) "ENABLED" else "DISABLED"}")
        publishSmokeUi(
            if (enabled) "Smoke: ENABLED (max 1 swipe; tap Run One-Step)"
            else "Smoke: DISABLED",
        )
    }

    fun resetSmokeSession() {
        smokeController.resetSession()
        publishSmokeUi("Smoke: session reset — ready for one swipe")
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
            publishSmokeUi("Smoke: no frame — Start capture + open Match Masters board first")
            return
        }
        smokeJob = viewModelScope.launch {
            _uiState.update { it.copy(smokeRunning = true) }
            publishSmokeUi("Smoke: running CAPTURE→VALIDATE→ANALYZE→SELECT…")
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
                        publishSmokeUi("Smoke HOLD: ${step.reason}")
                    }
                    is OneStepSmokeController.StepResult.Stopped -> {
                        publishSmokeUi("Smoke STOP: ${step.reason}")
                    }
                    is OneStepSmokeController.StepResult.SuccessReadyForNext -> {
                        publishSmokeUi("Smoke SUCCESS READY FOR NEXT")
                    }
                    is OneStepSmokeController.StepResult.AwaitingFeedback -> {
                        publishSmokeUi(
                            "Smoke: swipe dispatched — waiting ${step.animationWaitMs}ms…",
                        )
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
                            publishSmokeUi("Smoke STOP: no post-swipe frame")
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
                                    "Smoke SUCCESS READY FOR NEXT — board changed " +
                                        "${fb.beforeHash}→${fb.afterHash}",
                                )
                            is OneStepSmokeController.StepResult.Held ->
                                publishSmokeUi("Smoke HOLD: ${fb.reason}")
                            is OneStepSmokeController.StepResult.Stopped ->
                                publishSmokeUi("Smoke STOP: ${fb.reason}")
                            is OneStepSmokeController.StepResult.AwaitingFeedback ->
                                publishSmokeUi("Smoke: unexpected awaiting state")
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
            _uiState.update { it.copy(visionStatusText = "Vision: no frame to analyze") }
            return
        }
        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            _uiState.update { it.copy(visionStatusText = "Vision: analyzing…") }
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
                visionStatusText = "Vision: ${dbg.gate} · ${dbg.gridMethod} · unk=${dbg.unknownCount} · " +
                    "board=${"%.2f".format(dbg.boardConfidence)} grid=${"%.2f".format(dbg.gridConfidence)}",
                visionDebugText = "ROI ${dbg.roiText} · cells=${dbg.cellLabels.size}" +
                    (dbg.holdReason?.let { r -> " · $r" } ?: ""),
                boardGridLabels = dbg.cellLabels,
                gateHold = hold,
                holdMessage = if (hold) {
                    resolvedSnap.holdReason ?: dbg.holdReason ?: "HOLD — Decision AI blocked"
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
                val manager = CaptureService.managerOrNull()
                val frame = manager?.latestFrame?.value
                if (frame != null && frame !== lastSeen) {
                    lastSeen = frame
                    publishFrame(frame)
                }
                val capturing = manager?.isCapturing?.value == true
                if (!capturing && _uiState.value.status == AnalyzerStatus.Capturing) {
                    _uiState.update {
                        it.copy(status = AnalyzerStatus.Stopped, statusMessage = "Stopped")
                    }
                }
                delay(100)
            }
        }
    }

    private fun publishFrame(frame: CaptureFrame) {
        _uiState.update { state ->
            state.copy(
                status = AnalyzerStatus.Capturing,
                statusMessage = "Capturing ${frame.width}×${frame.height}",
                frameWidth = frame.width,
                frameHeight = frame.height,
                contentRoiText = formatRoi(frame.contentRoi),
                frameCount = state.frameCount + 1,
                lastFrameBitmap = frame.bitmap,
                lastContentRoi = frame.contentRoi,
            )
        }
    }

    private fun formatRoi(roi: ContentRoi?): String {
        if (roi == null) return "full frame"
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
