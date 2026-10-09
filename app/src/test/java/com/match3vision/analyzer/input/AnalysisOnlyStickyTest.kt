package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import com.match3vision.analyzer.capture.ScreenMeasurement
import com.match3vision.analyzer.vision.CellVision
import com.match3vision.analyzer.vision.GridGeometry
import com.match3vision.analyzer.vision.GridMethod
import com.match3vision.analyzer.vision.SpecialType
import com.match3vision.analyzer.vision.TileColor
import com.match3vision.analyzer.vision.TileShape
import com.match3vision.analyzer.vision.ValidationResult
import com.match3vision.analyzer.vision.VisionBoard
import com.match3vision.analyzer.vision.VisionResult
import com.match3vision.analyzer.vision.VisionThresholds
import org.junit.Test

/**
 * Analysis-only latches only when accessibility is off at INDÍTÁS.
 * Connecting the service clears that latch and does not dispatch.
 * Forcing the input switch on while the latch is held still does not dispatch.
 */
class AnalysisOnlyStickyTest {

    @Test
    fun a11yConnectsMidRun_clearsLatch_andNeverDispatches() {
        val (ctrl, exec) = diagnosticController()
        assertThat(ctrl.analysisOnlyBecauseA11yOff).isTrue()
        val gate = DiagnosticAnalysisGate.decide(
            captureOn = true,
            hasFrame = true,
            analysisOnly = false,
            a11yConnected = true,
            inputEnabled = false,
        )
        assertThat(gate.analyzeAndExport).isTrue()
        assertThat(gate.callRunCycle).isFalse()
        ctrl.clearAnalysisOnlyWhenAccessibilityConnects(true)
        assertThat(ctrl.analysisOnly).isFalse()
        assertThat(ctrl.analysisOnlyBecauseA11yOff).isFalse()
        assertThat(ctrl.enableSwitch().isEnabled()).isFalse()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.RUNNING)
        val cycle = ctrl.runCycleIfActive(passVision(), context(a11yConnected = true))
        assertThat(cycle).isNull()
        assertThat(exec.dispatched).isEmpty()
    }

    @Test
    fun a11yOnAtStart_doesNotLatchAnalysisOnly_andDoesNotDispatch() {
        CoordinateSelfCheck.clear()
        val exec = RecordingInputGestureExecutor(ready = true)
        val sw = InputEnableSwitch.disabledByDefault()
        val ctrl = AutoPlayController(
            enableSwitch = sw,
            inputLoop = InputLoopController(
                inputEngine = AutomaticInputEngine(enableSwitch = sw, executor = exec),
            ),
        )
        assertThat(
            ctrl.onDiagnosticStart(captureReady = true, overlayReady = true, a11yConnected = true),
        ).isTrue()
        assertThat(ctrl.analysisOnly).isFalse()
        assertThat(ctrl.analysisOnlyBecauseA11yOff).isFalse()
        assertThat(ctrl.enableSwitch().isEnabled()).isFalse()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.RUNNING)
        assertThat(ctrl.runCycleIfActive(passVision(), context(a11yConnected = true))).isNull()
        assertThat(exec.dispatched).isEmpty()
        assertThat(ctrl.armFiveMoveTest(0L, selfCheckThisSession = false, a11yConnected = true))
            .isFalse()
        assertThat(ctrl.lastReason).isEqualTo(FiveMoveArm.NEED_CALIBRATION)
        assertThat(exec.dispatched).isEmpty()
    }

    @Test
    fun inputSwitchOnDuringAnalysisOnly_neverDispatches() {
        val (ctrl, exec) = diagnosticController()
        ctrl.enableSwitch().setEnabled(true)
        assertThat(ctrl.enableSwitch().isEnabled()).isTrue()
        val cycle = ctrl.runCycleIfActive(passVision(), context(a11yConnected = true))
        assertThat(cycle).isNotNull()
        assertThat(cycle!!.gestureStatus).isEqualTo("NOT CREATED")
        assertThat(cycle.reason).contains("no touch")
        assertThat(exec.dispatched).isEmpty()
        assertThat(ctrl.enableSwitch().isEnabled()).isFalse()
        assertThat(ctrl.analysisOnly).isTrue()
    }

    @Test
    fun latchedAnalysisOnly_refusesContinuousStartUntilStop() {
        val (ctrl, exec) = diagnosticController()
        PlayPermit.allowContinuousStart()
        assertThat(
            ctrl.onStartRequested(a11yConnected = true, captureReady = true, overlayReady = true),
        ).isFalse()
        assertThat(ctrl.analysisOnly).isTrue()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.RUNNING)
        assertThat(ctrl.lastReason).contains("analysis-only until STOP")
        assertThat(ctrl.armSingleMove(a11yConnected = true)).isFalse()
        assertThat(ctrl.analysisOnly).isTrue()
        assertThat(exec.dispatched).isEmpty()

        ctrl.onBubblePause()
        assertThat(ctrl.analysisOnly).isTrue()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.PAUSED)

        ctrl.onBubbleStop()
        assertThat(ctrl.analysisOnly).isFalse()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.STOPPED)

        ctrl.resetForNewSession()
        assertThat(ctrl.analysisOnly).isFalse()
        PlayPermit.allowContinuousStart()
        assertThat(
            ctrl.onStartRequested(a11yConnected = true, captureReady = true, overlayReady = true),
        ).isTrue()
        assertThat(ctrl.analysisOnly).isFalse()
        val played = ctrl.runCycleIfActive(passVision(), context(a11yConnected = true))
        assertThat(played!!.executed).isInstanceOf(AutomaticInputEngine.ExecuteResult.Executed::class.java)
        assertThat(exec.dispatched).hasSize(1)
        assertThat(CoordinateSelfCheck.current()!!.alignmentProven).isFalse()
    }

    private fun diagnosticController(): Pair<AutoPlayController, RecordingInputGestureExecutor> {
        CoordinateSelfCheck.clear()
        val exec = RecordingInputGestureExecutor(ready = true)
        val sw = InputEnableSwitch.disabledByDefault()
        val ctrl = AutoPlayController(
            enableSwitch = sw,
            inputLoop = InputLoopController(
                inputEngine = AutomaticInputEngine(enableSwitch = sw, executor = exec),
            ),
        )
        assertThat(ctrl.onDiagnosticStart()).isTrue()
        assertThat(ctrl.analysisOnly).isTrue()
        assertThat(ctrl.enableSwitch().isEnabled()).isFalse()
        return ctrl to exec
    }

    private fun context(a11yConnected: Boolean) = ProductionCycleContext.fromLoopObservation(
        a11yConnected = a11yConnected,
        captureManagerPresent = true,
        hasFrame = true,
        frameAgeMs = 20L,
        frameSequenceDecision = null,
        frameTimestampMs = 5_000L,
        frameWidth = 700,
        frameHeight = 700,
        capturedElapsedMs = 10_000L,
        screen = ScreenMeasurement(
            widthPx = 700,
            heightPx = 700,
            densityDpi = 420,
            rotation = 0,
            source = ScreenMeasurement.SOURCE_MAXIMUM_WINDOW,
        ),
        frameSequence = 1L,
    )

    private fun passVision(): VisionResult {
        val palette = listOf(
            TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O, TileColor.R,
        )
        val colors = Array(7) { r -> Array(7) { c -> palette[(r * 3 + c * 2 + 1) % 6] } }
        colors[0][0] = TileColor.R
        colors[0][1] = TileColor.R
        colors[0][2] = TileColor.B
        colors[0][3] = TileColor.Y
        colors[1][2] = TileColor.R
        val cells = Array(7) { r ->
            Array(7) { c ->
                CellVision(
                    color = colors[r][c],
                    shape = TileShape.CIRCLE,
                    special = SpecialType.NONE,
                    occluded = false,
                    confidence = 1f,
                    isUnknown = false,
                )
            }
        }
        return VisionResult(
            board = VisionBoard(cells),
            grid = GridGeometry.evenSplit(ContentRoi(0, 0, 700, 700), 0.99f),
            unknownCount = 0,
            confidence = 1f,
            boardConfidence = VisionThresholds.MIN_BOARD_CONFIDENCE,
            gridConfidence = VisionThresholds.MIN_GRID_CONFIDENCE,
            validation = ValidationResult.Pass,
            method = GridMethod.EVEN_SPLIT,
        )
    }
}
