package com.match3vision.analyzer.overlay

import com.match3vision.analyzer.input.AccessibilityGestureExecutor
import com.match3vision.analyzer.input.AutoPlayController
import com.match3vision.analyzer.input.AutomaticInputEngine
import com.match3vision.analyzer.input.InputEnableSwitch
import com.match3vision.analyzer.input.InputLoopController
import com.match3vision.analyzer.orchestration.AnalysisOrchestrator
import com.match3vision.analyzer.vision.VisionFrameAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide auto-play session shared by MainActivity, FloatingBubbleService,
 * and AnalyzerViewModel. Input stays DISABLED until bubble INDÍTÁS.
 */
object AutoPlaySession {

    data class UiSnapshot(
        val mode: AutoPlayController.Mode = AutoPlayController.Mode.IDLE,
        val statusText: String = "Tétlen — nyomd meg az INDÍTÁS-t",
        val moveCount: Int = 0,
        val holdCount: Int = 0,
        val bubbleVisible: Boolean = false,
        val a11yReady: Boolean = false,
        val overlayReady: Boolean = false,
        val captureReady: Boolean = false,
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

    val frameAnalyzer: VisionFrameAnalyzer = VisionFrameAnalyzer()
    val orchestrator: AnalysisOrchestrator = AnalysisOrchestrator()

    private val _ui = MutableStateFlow(UiSnapshot())
    val ui: StateFlow<UiSnapshot> = _ui.asStateFlow()

    fun publish(
        statusText: String? = null,
        bubbleVisible: Boolean? = null,
        a11yReady: Boolean? = null,
        overlayReady: Boolean? = null,
        captureReady: Boolean? = null,
    ) {
        _ui.update { cur ->
            cur.copy(
                mode = controller.mode,
                statusText = statusText ?: controller.lastReason,
                moveCount = controller.moveCount,
                holdCount = controller.holdCount,
                bubbleVisible = bubbleVisible ?: cur.bubbleVisible,
                a11yReady = a11yReady ?: cur.a11yReady,
                overlayReady = overlayReady ?: cur.overlayReady,
                captureReady = captureReady ?: cur.captureReady,
            )
        }
    }

    fun refreshFromController(statusOverride: String? = null) {
        publish(statusText = statusOverride)
    }

    fun beginNewSession() {
        controller.resetForNewSession()
        publish(
            statusText = "Kész — buborék INDÍTÁS indítja a kört",
            bubbleVisible = true,
        )
    }

    fun endSession() {
        if (controller.mode != AutoPlayController.Mode.STOPPED) {
            controller.onBubbleStop("session end")
        }
        publish(statusText = "Leállítva", bubbleVisible = false)
    }
}
