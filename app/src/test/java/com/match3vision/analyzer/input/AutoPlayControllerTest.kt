package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.capture.ContentRoi
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

class AutoPlayControllerTest {

    private fun cell(color: TileColor) = CellVision(
        color = color,
        shape = when (color) {
            TileColor.R -> TileShape.CIRCLE
            TileColor.B -> TileShape.STAR
            TileColor.Y -> TileShape.TRIANGLE
            TileColor.G -> TileShape.DIAMOND
            TileColor.P -> TileShape.SQUARE
            TileColor.O -> TileShape.HEX
            TileColor.UNKNOWN -> TileShape.UNKNOWN
        },
        special = SpecialType.NONE,
        occluded = false,
        confidence = 1f,
        isUnknown = color == TileColor.UNKNOWN,
    )

    private fun puzzleColors(): Array<Array<TileColor>> {
        val palette = listOf(
            TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O, TileColor.R,
        )
        val colors = Array(7) { r ->
            Array(7) { c -> palette[(r * 3 + c * 2) % 6] }
        }
        colors[0][0] = TileColor.R
        colors[0][1] = TileColor.R
        colors[0][2] = TileColor.B
        colors[0][3] = TileColor.Y
        colors[1][2] = TileColor.R
        return colors
    }

    private fun vision(
        colors: Array<Array<TileColor>> = puzzleColors(),
        gate: ValidationResult = ValidationResult.Pass,
        boardConf: Float = VisionThresholds.MIN_BOARD_CONFIDENCE,
        gridConf: Float = VisionThresholds.MIN_GRID_CONFIDENCE,
    ): VisionResult {
        val cells = Array(7) { r -> Array(7) { c -> cell(colors[r][c]) } }
        val unk = colors.sumOf { row -> row.count { it == TileColor.UNKNOWN } }
        val roi = ContentRoi(0, 0, 700, 700)
        return VisionResult(
            board = VisionBoard(cells),
            grid = GridGeometry.evenSplit(roi, confidence = gridConf),
            unknownCount = unk,
            confidence = boardConf,
            boardConfidence = boardConf,
            gridConfidence = gridConf,
            validation = gate,
            method = GridMethod.EVEN_SPLIT,
        )
    }

    private fun controllerWithReadyExec(): Pair<AutoPlayController, RecordingInputGestureExecutor> {
        val exec = RecordingInputGestureExecutor(ready = true)
        val sw = InputEnableSwitch.disabledByDefault()
        val eng = AutomaticInputEngine(enableSwitch = sw, executor = exec)
        val loop = InputLoopController(inputEngine = eng)
        val ctrl = AutoPlayController(enableSwitch = sw, inputLoop = loop)
        return ctrl to exec
    }

    @Test
    fun default_noCycleBeforeBubbleStart() {
        val (ctrl, exec) = controllerWithReadyExec()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.IDLE)
        assertThat(ctrl.enableSwitch().isEnabled()).isFalse()
        val cycle = ctrl.runCycleIfActive(vision())
        assertThat(cycle).isNull()
        assertThat(exec.dispatched).isEmpty()
        assertThat(ctrl.moveCount).isEqualTo(0)
    }

    @Test
    fun bubbleStart_thenCycleExecutes_pauseStopsFurtherInput() {
        val (ctrl, exec) = controllerWithReadyExec()
        assertThat(ctrl.onBubbleStart()).isTrue()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.RUNNING)
        assertThat(ctrl.enableSwitch().isEnabled()).isTrue()

        val cycle = ctrl.runCycleIfActive(vision())
        assertThat(cycle).isNotNull()
        assertThat(cycle!!.outcome).isEqualTo(BotLoopOutcome.CONTINUE)
        assertThat(cycle.executed)
            .isInstanceOf(AutomaticInputEngine.ExecuteResult.Executed::class.java)
        assertThat(exec.dispatched).hasSize(1)
        assertThat(ctrl.moveCount).isEqualTo(1)

        val executed = cycle.executed as AutomaticInputEngine.ExecuteResult.Executed
        val afterColors = puzzleColors()
        afterColors[6][6] = TileColor.R
        val after = vision(colors = afterColors)
        assertThat(Board.fromVision(after.board).contentHash())
            .isNotEqualTo(executed.beforeBoardHash)
        val fb = ctrl.completeFeedback(executed.beforeBoardHash, after)
        assertThat(fb!!.outcome).isEqualTo(BotLoopOutcome.CONTINUE)

        ctrl.onBubblePause()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.PAUSED)
        assertThat(ctrl.enableSwitch().isEnabled()).isFalse()
        assertThat(ctrl.runCycleIfActive(vision())).isNull()
        assertThat(exec.dispatched).hasSize(1) // no second swipe while paused
    }

    @Test
    fun bubbleStop_isTerminal_noMoreCycles() {
        val (ctrl, exec) = controllerWithReadyExec()
        ctrl.onBubbleStart()
        ctrl.onBubbleStop()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.STOPPED)
        assertThat(ctrl.enableSwitch().isEnabled()).isFalse()
        assertThat(ctrl.onBubbleStart()).isFalse()
        assertThat(ctrl.runCycleIfActive(vision())).isNull()
        assertThat(exec.dispatched).isEmpty()
    }

    @Test
    fun visionHold_whileRunning_returnsHold_noGesture() {
        val (ctrl, exec) = controllerWithReadyExec()
        ctrl.onBubbleStart()
        val cycle = ctrl.runCycleIfActive(
            vision(gate = ValidationResult.Hold("HOLD — Decision AI blocked")),
        )
        assertThat(cycle!!.outcome).isEqualTo(BotLoopOutcome.HOLD)
        assertThat(exec.dispatched).isEmpty()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.RUNNING)
    }

    @Test
    fun resetForNewSession_allowsStartAgain() {
        val (ctrl, _) = controllerWithReadyExec()
        ctrl.onBubbleStart()
        ctrl.onBubbleStop()
        ctrl.resetForNewSession()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.IDLE)
        assertThat(ctrl.onBubbleStart()).isTrue()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.RUNNING)
    }

    @Test
    fun a11yDisconnected_blocksRunning_evenWithSettingsFlag() {
        val (ctrl, exec) = controllerWithReadyExec()
        assertThat(
            ctrl.onBubbleStart(
                a11yConnected = false,
                settingsEnabled = true,
                captureReady = true,
                overlayReady = true,
            ),
        ).isFalse()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.IDLE)
        assertThat(ctrl.enableSwitch().isEnabled()).isFalse()
        assertThat(ctrl.runCycleIfActive(vision())).isNull()
        assertThat(exec.dispatched).isEmpty()
        assertThat(ctrl.lastReason).contains("ACCESSIBILITY: DISCONNECTED")
    }
}
