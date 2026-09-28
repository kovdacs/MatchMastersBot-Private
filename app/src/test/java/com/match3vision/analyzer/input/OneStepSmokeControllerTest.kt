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

/**
 * Controlled one-step smoke: max 1 swipe, gates, reset, no continuous loop.
 */
class OneStepSmokeControllerTest {

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
        // Legal 3-match swap (0,2)↔(1,2)
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
        roi: ContentRoi = ContentRoi(0, 0, 700, 700),
    ): VisionResult {
        val cells = Array(7) { r -> Array(7) { c -> cell(colors[r][c]) } }
        val unk = colors.sumOf { row -> row.count { it == TileColor.UNKNOWN } }
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

    private fun changedVision(before: VisionResult): VisionResult {
        val colors = puzzleColors()
        colors[0][0] = TileColor.O
        colors[0][1] = TileColor.O
        colors[0][2] = TileColor.O
        val after = vision(colors = colors)
        assertThat(Board.fromVision(after.board).contentHash())
            .isNotEqualTo(Board.fromVision(before.board).contentHash())
        return after
    }

    private fun controller(
        smoke: Boolean = true,
        input: Boolean = true,
        ready: Boolean = true,
    ): Triple<OneStepSmokeController, RecordingInputGestureExecutor, SmokeTestLogger> {
        val exec = RecordingInputGestureExecutor(ready = ready)
        val logger = SmokeTestLogger()
        val eng = AutomaticInputEngine(
            enableSwitch = InputEnableSwitch(initiallyEnabled = input),
            executor = exec,
        )
        val ctrl = OneStepSmokeController(
            smokeEnable = SmokeEnableSwitch(initiallyEnabled = smoke),
            inputEngine = eng,
            logger = logger,
        )
        return Triple(ctrl, exec, logger)
    }

    @Test
    fun smokeEnableSwitch_defaultsDisabled() {
        val sw = SmokeEnableSwitch.disabledByDefault()
        assertThat(sw.isEnabled()).isFalse()
        val ctrl = OneStepSmokeController()
        assertThat(ctrl.isSmokeEnabled()).isFalse()
        assertThat(ctrl.inputEngine().isInputEnabled()).isFalse()
    }

    @Test
    fun smokeDisabled_blocksEvenWhenInputEnabledAndVisionPass() {
        val (ctrl, exec, _) = controller(smoke = false, input = true)
        val result = ctrl.runOneStep(vision())
        assertThat(result).isInstanceOf(OneStepSmokeController.StepResult.Held::class.java)
        assertThat((result as OneStepSmokeController.StepResult.Held).reason)
            .contains("smoke disabled")
        assertThat(exec.dispatched).isEmpty()
        assertThat(ctrl.swipeCount).isEqualTo(0)
    }

    @Test
    fun inputDisabled_blocksEvenWhenSmokeEnabled() {
        val (ctrl, exec, _) = controller(smoke = true, input = false)
        val result = ctrl.runOneStep(vision())
        assertThat(result).isInstanceOf(OneStepSmokeController.StepResult.Held::class.java)
        assertThat((result as OneStepSmokeController.StepResult.Held).reason)
            .contains("input disabled")
        assertThat(exec.dispatched).isEmpty()
    }

    @Test
    fun visionHold_blocks_noSwipe() {
        val (ctrl, exec, _) = controller()
        val result = ctrl.runOneStep(
            vision(gate = ValidationResult.Hold("HOLD — Decision AI blocked")),
        )
        assertThat(result).isInstanceOf(OneStepSmokeController.StepResult.Held::class.java)
        assertThat(exec.dispatched).isEmpty()
    }

    @Test
    fun lowGridConfidence_blocked() {
        val (ctrl, exec, _) = controller()
        val result = ctrl.runOneStep(vision(gridConf = 0.90f))
        assertThat(result).isInstanceOf(OneStepSmokeController.StepResult.Held::class.java)
        assertThat(exec.dispatched).isEmpty()
    }

    @Test
    fun unknownCountAboveOne_blocked() {
        val (ctrl, exec, _) = controller()
        val colors = puzzleColors()
        colors[0][0] = TileColor.UNKNOWN
        colors[3][3] = TileColor.UNKNOWN
        val result = ctrl.runOneStep(vision(colors = colors))
        assertThat(result).isInstanceOf(OneStepSmokeController.StepResult.Held::class.java)
        assertThat(exec.dispatched).isEmpty()
    }

    @Test
    fun oneStep_dispatchesExactlyOneSwipe_fromLiveGridCenters() {
        val (ctrl, exec, logger) = controller()
        val before = vision(roi = ContentRoi(40, 80, 740, 780))
        val result = ctrl.runOneStep(before)
        assertThat(result).isInstanceOf(OneStepSmokeController.StepResult.AwaitingFeedback::class.java)
        val await = result as OneStepSmokeController.StepResult.AwaitingFeedback
        assertThat(exec.dispatched).hasSize(1)
        assertThat(ctrl.swipeCount).isEqualTo(1)
        assertThat(ctrl.phase).isEqualTo(OneStepSmokeController.Phase.AWAITING_FEEDBACK)

        val move = await.move.move
        val from = before.grid.cellBox(move.r1, move.c1)
        val to = before.grid.cellBox(move.r2, move.c2)
        assertThat(await.gesture.startX).isWithin(0.01f).of(from.centerX())
        assertThat(await.gesture.startY).isWithin(0.01f).of(from.centerY())
        assertThat(await.gesture.endX).isWithin(0.01f).of(to.centerX())
        assertThat(await.gesture.endY).isWithin(0.01f).of(to.centerY())
        // Not hardcoded pvp origin — centers inside live ROI.
        assertThat(await.gesture.startX).isGreaterThan(40f)
        assertThat(await.gesture.startY).isGreaterThan(80f)

        assertThat(logger.dump()).contains("SELECT")
        assertThat(logger.dump()).contains("INPUT_DISPATCHED")
        assertThat(logger.dump()).contains("EV=")
    }

    @Test
    fun feedbackSuccess_readyForNext_secondSwipeBlockedUntilReset() {
        val (ctrl, exec, logger) = controller()
        val before = vision()
        val await = ctrl.runOneStep(before) as OneStepSmokeController.StepResult.AwaitingFeedback
        val after = changedVision(before)
        val done = ctrl.completeWithNewFrame(after)
        assertThat(done).isInstanceOf(OneStepSmokeController.StepResult.SuccessReadyForNext::class.java)
        assertThat(ctrl.phase).isEqualTo(OneStepSmokeController.Phase.SUCCESS_READY_FOR_NEXT)
        assertThat(logger.dump()).contains("BOARD_CHANGED")
        assertThat(logger.dump()).contains("SUCCESS READY FOR NEXT")

        // Second swipe blocked
        val again = ctrl.runOneStep(after)
        assertThat(again).isInstanceOf(OneStepSmokeController.StepResult.Held::class.java)
        assertThat((again as OneStepSmokeController.StepResult.Held).reason)
            .contains("reset required")
        assertThat(exec.dispatched).hasSize(1)
        assertThat(ctrl.swipeCount).isEqualTo(1)

        // Reset unlocks another one-step
        ctrl.resetSession()
        assertThat(ctrl.swipeCount).isEqualTo(0)
        assertThat(ctrl.phase).isEqualTo(OneStepSmokeController.Phase.IDLE)
        val second = ctrl.runOneStep(after)
        assertThat(second).isInstanceOf(OneStepSmokeController.StepResult.AwaitingFeedback::class.java)
        assertThat(exec.dispatched).hasSize(2)
        assertThat(ctrl.swipeCount).isEqualTo(1)
    }

    @Test
    fun boardUnchanged_STOP_noBlindRetry_noSecondSwipe() {
        val (ctrl, exec, _) = controller()
        val before = vision()
        ctrl.runOneStep(before)
        val fb = ctrl.completeWithNewFrame(before) // same board
        assertThat(fb).isInstanceOf(OneStepSmokeController.StepResult.Stopped::class.java)
        assertThat(ctrl.phase).isEqualTo(OneStepSmokeController.Phase.STOP)
        assertThat(exec.dispatched).hasSize(1)

        val again = ctrl.runOneStep(before)
        assertThat(again).isInstanceOf(OneStepSmokeController.StepResult.Stopped::class.java)
        assertThat(exec.dispatched).hasSize(1)
    }

    @Test
    fun channelNotReady_hold() {
        val (ctrl, exec, _) = controller(ready = false)
        val result = ctrl.runOneStep(vision())
        assertThat(result).isInstanceOf(OneStepSmokeController.StepResult.Held::class.java)
        assertThat(exec.dispatched).isEmpty()
    }

    @Test
    fun maxSwipesConstant_isOne() {
        assertThat(OneStepSmokeController.MAX_SWIPES_PER_SESSION).isEqualTo(1)
    }
}
