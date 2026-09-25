package com.match3vision.analyzer.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.match3vision.analyzer.capture.CaptureFrame
import com.match3vision.analyzer.capture.CaptureService
import com.match3vision.analyzer.capture.ContentRoi
import com.match3vision.analyzer.orchestration.AnalysisOrchestrator
import com.match3vision.analyzer.orchestration.AnalysisUiSnapshot
import com.match3vision.analyzer.vision.VisionFrameAnalyzer
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
) {
    companion object {
        const val SUBTITLE = "Analyzer only — no automatic input"
    }
}

/**
 * Binds [CaptureService] frame flow to Compose UI state.
 * Analyze last frame uses the **real** last captured bitmap (no fake image).
 * Decision output is display-only — never executes input.
 */
class AnalyzerViewModel(
    private val frameAnalyzer: VisionFrameAnalyzer = VisionFrameAnalyzer(),
    private val orchestrator: AnalysisOrchestrator = AnalysisOrchestrator(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnalyzerUiState())
    val uiState: StateFlow<AnalyzerUiState> = _uiState.asStateFlow()

    private var observeJob: Job? = null
    private var analyzeJob: Job? = null

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
                val w = bitmap.width
                val h = bitmap.height
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                val frameAnalysis = frameAnalyzer.analyzePixels(pixels, w, h, roi)
                val snap = orchestrator.analyzeVisionResult(frameAnalysis.result)
                frameAnalysis to snap
            }
            val (frameAnalysis, snap) = analysis
            val dbg = frameAnalysis.debugSummary
            val hold = dbg.gate == "HOLD" || snap.decisionBlocked
            _uiState.update {
                it.copy(
                    visionStatusText = "Vision: ${dbg.gate} · ${dbg.gridMethod} · unk=${dbg.unknownCount} · " +
                        "board=${"%.2f".format(dbg.boardConfidence)} grid=${"%.2f".format(dbg.gridConfidence)}",
                    visionDebugText = "ROI ${dbg.roiText} · cells=${dbg.cellLabels.size}" +
                        (dbg.holdReason?.let { r -> " · $r" } ?: ""),
                    boardGridLabels = dbg.cellLabels,
                    gateHold = hold,
                    holdMessage = if (hold) {
                        snap.holdReason ?: dbg.holdReason ?: "HOLD — Decision AI blocked"
                    } else null,
                    topMovesText = if (hold) emptyList() else snap.topMovesLines,
                    whyText = snap.whyText,
                    confidenceText = snap.confidenceText,
                    riskText = snap.riskText,
                    expectedValueText = snap.expectedValueText,
                    gameStateText = snap.gameStateText,
                    analysisSnapshot = snap,
                )
            }
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
        super.onCleared()
    }
}
