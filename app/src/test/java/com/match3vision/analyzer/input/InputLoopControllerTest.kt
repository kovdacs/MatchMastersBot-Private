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

class InputLoopControllerTest {

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

    @Test
    fun defaultDisabled_analyzesButDoesNotInput() {
        val exec = RecordingInputGestureExecutor(ready = true)
        val eng = AutomaticInputEngine(
            enableSwitch = InputEnableSwitch.disabledByDefault(),
            executor = exec,
        )
        val ctrl = InputLoopController(inputEngine = eng)
        val cycle = ctrl.runAnalyzeAndMaybeInput(vision())
        assertThat(cycle.outcome).isEqualTo(BotLoopOutcome.HOLD)
        assertThat(cycle.reason).contains("input disabled")
        assertThat(cycle.topMove).isNotNull()
        assertThat(exec.dispatched).isEmpty()
    }

    @Test
    fun enabled_executes_thenFeedbackSuccess() {
        val exec = RecordingInputGestureExecutor(ready = true)
        val sw = InputEnableSwitch(initiallyEnabled = true)
        val eng = AutomaticInputEngine(enableSwitch = sw, executor = exec)
        val ctrl = InputLoopController(inputEngine = eng)

        val before = vision()
        val cycle = ctrl.runAnalyzeAndMaybeInput(before)
        assertThat(cycle.reason).isNotEmpty()
        assertThat(cycle.outcome).isEqualTo(BotLoopOutcome.CONTINUE)
        assertThat(cycle.executed)
            .isInstanceOf(AutomaticInputEngine.ExecuteResult.Executed::class.java)
        assertThat(exec.dispatched).hasSize(1)
        assertThat(cycle.animationWaitMs).isGreaterThan(0L)

        val executed = cycle.executed as AutomaticInputEngine.ExecuteResult.Executed
        val colorsAfter = puzzleColors()
        colorsAfter[6][6] = TileColor.R // force hash change
        // keep a legal-looking board; just change one cell
        val after = vision(colors = colorsAfter)
        assertThat(Board.fromVision(after.board).contentHash())
            .isNotEqualTo(executed.beforeBoardHash)

        val fb = ctrl.completeFeedback(
            executed.beforeBoardHash,
            after,
            VerifyObservation(
                newFrameAccepted = true,
                frameFresh = true,
                gestureEligible = true,
                frameElapsedMs = 6_000L,
                dispatchCompletedElapsedMs = 5_000L,
                preDispatchSequence = 1L,
                afterSequence = 2L,
            ),
        )
        assertThat(fb.verifyStatus).isEqualTo(VerificationPolicy.BOARD_CHANGED_UNCONFIRMED)
        assertThat(fb.verifyStatus).isNotEqualTo(VerificationPolicy.SUCCESS)
        assertThat(fb.outcome).isEqualTo(BotLoopOutcome.CONTINUE)
        assertThat(fb.feedback)
            .isInstanceOf(AutomaticInputEngine.FeedbackResult.Success::class.java)
        assertThat(eng.stateMachine().state).isEqualTo(BotLoopState.CAPTURE)
    }

    @Test
    fun visionHold_noInput() {
        val exec = RecordingInputGestureExecutor(ready = true)
        val eng = AutomaticInputEngine(
            enableSwitch = InputEnableSwitch(initiallyEnabled = true),
            executor = exec,
        )
        val ctrl = InputLoopController(inputEngine = eng)
        val cycle = ctrl.runAnalyzeAndMaybeInput(
            vision(gate = ValidationResult.Hold("HOLD — Decision AI blocked")),
        )
        assertThat(cycle.outcome).isEqualTo(BotLoopOutcome.HOLD)
        assertThat(exec.dispatched).isEmpty()
    }
}
