package com.match3vision.analyzer.overlay

import com.match3vision.analyzer.capture.AnalysisFrameGate
import com.match3vision.analyzer.input.AccessibilityGestureExecutor
import com.match3vision.analyzer.input.AutoPlayController
import com.match3vision.analyzer.input.AutomaticInputEngine
import com.match3vision.analyzer.input.AutomaticTouchTest
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
        /** True while loop RUNNING — analyzer UI should stay compact / backgrounded. */
        val compactUi: Boolean = false,
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

    private val _ui = MutableStateFlow(UiSnapshot())
    val ui: StateFlow<UiSnapshot> = _ui.asStateFlow()

    fun publish(
        statusText: String? = null,
        bubbleVisible: Boolean? = null,
        a11yReady: Boolean? = null,
        overlayReady: Boolean? = null,
        captureReady: Boolean? = null,
    ) {
        val visible = bubbleVisible ?: _ui.value.bubbleVisible
        frameGate.setBubbleOverlayOnly(visible)
        frameGate.setBubbleLoopRunning(controller.mode == AutoPlayController.Mode.RUNNING)
        _ui.update { cur ->
            cur.copy(
                mode = controller.mode,
                statusText = statusText ?: controller.lastReason,
                moveCount = controller.moveCount,
                holdCount = controller.holdCount,
                bubbleVisible = visible,
                a11yReady = a11yReady ?: cur.a11yReady,
                overlayReady = overlayReady ?: cur.overlayReady,
                captureReady = captureReady ?: cur.captureReady,
                compactUi = controller.mode == AutoPlayController.Mode.RUNNING,
            )
        }
    }

    fun refreshFromController(statusOverride: String? = null) {
        publish(statusText = statusOverride)
    }

    fun beginNewSession() {
        controller.resetForNewSession()
        frameGate.setBubbleLoopRunning(false)
        publish(
            statusText = "Kész — buborék INDÍTÁS indítja a kört",
            bubbleVisible = true,
        )
    }

    fun endSession() {
        if (controller.mode != AutoPlayController.Mode.STOPPED) {
            controller.onBubbleStop("session end")
        }
        frameGate.setBubbleLoopRunning(false)
        frameGate.setBubbleOverlayOnly(false)
        publish(statusText = "Leállítva", bubbleVisible = false)
    }

    /** Sync gate flags after bubble START / PAUSE / STOP. */
    fun syncFrameGateFromMode() {
        frameGate.setBubbleLoopRunning(controller.mode == AutoPlayController.Mode.RUNNING)
        frameGate.setBubbleOverlayOnly(_ui.value.bubbleVisible)
        _ui.update { it.copy(mode = controller.mode, compactUi = controller.mode == AutoPlayController.Mode.RUNNING) }
    }
}
