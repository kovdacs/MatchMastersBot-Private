package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import com.match3vision.analyzer.capture.FrameSequenceGate
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

/** START → RUNNING → STOP → START: no stale frame/move/sequence/coroutine/input state. */
class RestartCycleTest {

    private fun cell(color: TileColor) = CellVision(
        color = color,
        shape = TileShape.CIRCLE,
        special = SpecialType.NONE,
        occluded = false,
        confidence = 1f,
        isUnknown = color == TileColor.UNKNOWN,
    )

    private fun vision(): VisionResult {
        val colors = Array(7) { r ->
            Array(7) { c ->
                listOf(TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O, TileColor.R)[(r + c) % 6]
            }
        }
        // Legal horizontal match setup
        colors[0][0] = TileColor.R
        colors[0][1] = TileColor.R
        colors[0][2] = TileColor.B
        colors[1][2] = TileColor.R
        val cells = Array(7) { r -> Array(7) { c -> cell(colors[r][c]) } }
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

    @Test
    fun startStopStart_clearsStaleState() {
        val exec = RecordingInputGestureExecutor(ready = true)
        val sw = InputEnableSwitch.disabledByDefault()
        val eng = AutomaticInputEngine(enableSwitch = sw, executor = exec)
        val loop = InputLoopController(inputEngine = eng)
        val ctrl = AutoPlayController(enableSwitch = sw, inputLoop = loop)
        val seq = FrameSequenceGate()

        assertThat(ctrl.onStartRequested()).isTrue()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.RUNNING)
        val c1 = ctrl.runCycleIfActive(vision())
        assertThat(c1).isNotNull()
        if (c1!!.executed is AutomaticInputEngine.ExecuteResult.Executed) {
            seq.markGestureDispatched(1, 10, 1000L)
            assertThat(seq.requireNewAfterGesture).isTrue()
        }

        ctrl.onBubbleStop("test stop")
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.STOPPED)
        assertThat(sw.isEnabled()).isFalse()
        assertThat(eng.stateMachine().state).isEqualTo(BotLoopState.STOP)

        // New session must clear mode/input/seq/state machine.
        ctrl.resetForNewSession()
        seq.reset()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.IDLE)
        assertThat(ctrl.moveCount).isEqualTo(0)
        assertThat(ctrl.holdCount).isEqualTo(0)
        assertThat(sw.isEnabled()).isFalse()
        assertThat(eng.stateMachine().state).isEqualTo(BotLoopState.IDLE)
        assertThat(seq.requireNewAfterGesture).isFalse()
        assertThat(AutoPlayTrace.lastStopReason).isNull()

        assertThat(ctrl.onStartRequested()).isTrue()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.RUNNING)
        assertThat(ctrl.runCycleIfActive(vision())).isNotNull()
    }
}

    @Test
    fun a11yDisconnect_blocksStart_reconnectAllowsStart() {
        val exec = RecordingInputGestureExecutor(ready = true)
        val sw = InputEnableSwitch.disabledByDefault()
        val eng = AutomaticInputEngine(enableSwitch = sw, executor = exec)
        val loop = InputLoopController(inputEngine = eng)
        val ctrl = AutoPlayController(enableSwitch = sw, inputLoop = loop)

        assertThat(ctrl.onStartRequested(a11yConnected = false)).isFalse()
        assertThat(ctrl.mode).isNotEqualTo(AutoPlayController.Mode.RUNNING)
        assertThat(ctrl.lastReason.uppercase()).contains("ACCESSIBILITY")

        assertThat(ctrl.onStartRequested(a11yConnected = true)).isTrue()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.RUNNING)

        // Mid-run failsafe pause (simulates bubble detecting disconnect)
        ctrl.onFailsafePause("ACCESSIBILITY: DISCONNECTED (mid-run)")
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.PAUSED)
        assertThat(sw.isEnabled()).isFalse()

        // Reconnect + INDÍTÁS resume
        assertThat(ctrl.onBubbleStart(a11yConnected = true)).isTrue()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.RUNNING)
        assertThat(sw.isEnabled()).isTrue()
    }

