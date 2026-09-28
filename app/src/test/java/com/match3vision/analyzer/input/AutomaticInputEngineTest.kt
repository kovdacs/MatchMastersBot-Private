package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.capture.ContentRoi
import com.match3vision.analyzer.evaluation.MoveEvaluation
import com.match3vision.analyzer.moves.Move
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
 * AUTOMATIC INPUT ENGINE V1 — gate, coords, enable switch, feedback loop, fail-safe.
 */
class AutomaticInputEngineTest {

    private fun cell(color: TileColor, conf: Float = 1f) = CellVision(
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
        confidence = conf,
        isUnknown = color == TileColor.UNKNOWN,
    )

    private fun baseColors(): Array<Array<TileColor>> {
        val palette = listOf(
            TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O, TileColor.R,
        )
        return Array(7) { r ->
            Array(7) { c -> palette[(r * 3 + c * 2) % 6] }
        }
    }

    private fun vision(
        colors: Array<Array<TileColor>> = baseColors(),
        gate: ValidationResult = ValidationResult.Pass,
        boardConf: Float = VisionThresholds.MIN_BOARD_CONFIDENCE,
        gridConf: Float = VisionThresholds.MIN_GRID_CONFIDENCE,
        roi: ContentRoi = ContentRoi(100, 200, 800, 900),
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

    private fun goodMove(
        move: Move = Move(0, 0, 0, 1),
        confidence: Float = 0.95f,
        uncertain: Boolean = false,
        ev: Float = 42f,
    ) = MoveEvaluation(
        move = move,
        totalScore = ev,
        matchScore = 30f,
        cascadeScore = 5f,
        specialScore = 0f,
        starScore = 0f,
        boosterScore = 0f,
        futureScore = 2f,
        riskPenalty = 0f,
        expectedValue = ev,
        confidence = confidence,
        uncertain = uncertain,
        reasons = listOf("test move"),
    )

    private fun engine(
        enabled: Boolean = true,
        ready: Boolean = true,
    ): Pair<AutomaticInputEngine, RecordingInputGestureExecutor> {
        val exec = RecordingInputGestureExecutor(ready = ready)
        val sw = InputEnableSwitch(initiallyEnabled = enabled)
        val eng = AutomaticInputEngine(enableSwitch = sw, executor = exec)
        return eng to exec
    }

    // ---- enable switch default ----

    @Test
    fun inputEnableSwitch_defaultsDisabled() {
        val sw = InputEnableSwitch.disabledByDefault()
        assertThat(sw.isEnabled()).isFalse()
        val eng = AutomaticInputEngine()
        assertThat(eng.isInputEnabled()).isFalse()
    }

    @Test
    fun inputDisabled_blocksEvenWhenVisionPass() {
        val (eng, exec) = engine(enabled = false, ready = true)
        val result = eng.tryExecute(vision(), goodMove())
        assertThat(result).isInstanceOf(AutomaticInputEngine.ExecuteResult.Held::class.java)
        val held = result as AutomaticInputEngine.ExecuteResult.Held
        assertThat(held.reason).contains("input disabled")
        assertThat(exec.dispatched).isEmpty()
        assertThat(eng.stateMachine().isHolding).isTrue()
    }

    // ---- PASS allowed ----

    @Test
    fun passGates_allowed_dispatchesSwipeFromCellCenters() {
        val (eng, exec) = engine(enabled = true, ready = true)
        val v = vision(roi = ContentRoi(0, 0, 700, 700))
        val move = goodMove(Move(1, 2, 1, 3))
        val result = eng.tryExecute(v, move)
        assertThat(result).isInstanceOf(AutomaticInputEngine.ExecuteResult.Executed::class.java)
        val ok = result as AutomaticInputEngine.ExecuteResult.Executed
        assertThat(exec.dispatched).hasSize(1)
        val g = ok.gesture
        // EVEN_SPLIT 700×700: cell (1,2) center = (2.5/7*700, 1.5/7*700) = (250, 150)
        // cell (1,3) center = (3.5/7*700, 1.5/7*700) = (350, 150)
        val from = v.grid.cellBox(1, 2)
        val to = v.grid.cellBox(1, 3)
        assertThat(g.startX).isWithin(0.01f).of(from.centerX())
        assertThat(g.startY).isWithin(0.01f).of(from.centerY())
        assertThat(g.endX).isWithin(0.01f).of(to.centerX())
        assertThat(g.endY).isWithin(0.01f).of(to.centerY())
        assertThat(g.startX).isWithin(0.01f).of(250f)
        assertThat(g.endX).isWithin(0.01f).of(350f)
        assertThat(eng.stateMachine().state).isEqualTo(BotLoopState.WAIT_FOR_BOARD)
    }

    // ---- HOLD blocked cases ----

    @Test
    fun visionHold_blocksInput() {
        val (eng, exec) = engine()
        val v = vision(gate = ValidationResult.Hold("HOLD — Decision AI blocked"))
        val result = eng.tryExecute(v, goodMove())
        assertThat(result).isInstanceOf(AutomaticInputEngine.ExecuteResult.Held::class.java)
        assertThat(exec.dispatched).isEmpty()
    }

    @Test
    fun lowGridConfidence_blocked() {
        val (eng, exec) = engine()
        val v = vision(gridConf = 0.90f, gate = ValidationResult.Pass)
        // Even if validation field says Pass, threshold re-check must HOLD.
        val gate = eng.evaluateGate(v, goodMove())
        assertThat(gate.allow).isFalse()
        assertThat(gate.reason.lowercase()).contains("grid")
        assertThat(eng.tryExecute(v, goodMove()))
            .isInstanceOf(AutomaticInputEngine.ExecuteResult.Held::class.java)
        assertThat(exec.dispatched).isEmpty()
    }

    @Test
    fun lowBoardConfidence_blocked() {
        val (eng, exec) = engine()
        val v = vision(boardConf = 0.80f)
        val gate = eng.evaluateGate(v, goodMove())
        assertThat(gate.allow).isFalse()
        assertThat(gate.reason.lowercase()).contains("board")
        assertThat(exec.dispatched).isEmpty()
    }

    @Test
    fun unknownCountAboveOne_blocked() {
        val (eng, exec) = engine()
        val colors = baseColors()
        colors[0][0] = TileColor.UNKNOWN
        colors[3][3] = TileColor.UNKNOWN
        val v = vision(colors = colors)
        assertThat(v.unknownCount).isGreaterThan(1)
        val gate = eng.evaluateGate(v, goodMove())
        assertThat(gate.allow).isFalse()
        assertThat(gate.reason.lowercase()).contains("unknown")
        assertThat(exec.dispatched).isEmpty()
    }

    @Test
    fun noLegalMove_blocked() {
        val (eng, exec) = engine()
        val result = eng.tryExecute(vision(), move = null)
        assertThat(result).isInstanceOf(AutomaticInputEngine.ExecuteResult.Held::class.java)
        assertThat((result as AutomaticInputEngine.ExecuteResult.Held).reason)
            .contains("no valid MoveEvaluation")
        assertThat(exec.dispatched).isEmpty()
    }

    @Test
    fun illegalMoveEv_blocked() {
        val (eng, exec) = engine()
        val bad = goodMove(ev = Float.NEGATIVE_INFINITY, confidence = 0.99f)
        val result = eng.tryExecute(vision(), bad)
        assertThat(result).isInstanceOf(AutomaticInputEngine.ExecuteResult.Held::class.java)
        assertThat(exec.dispatched).isEmpty()
    }

    @Test
    fun lowMoveConfidence_blocked() {
        val (eng, exec) = engine()
        val low = goodMove(confidence = 0.40f)
        val gate = eng.evaluateGate(vision(), low)
        assertThat(gate.allow).isFalse()
        assertThat(gate.reason).contains("move confidence")
        assertThat(eng.tryExecute(vision(), low))
            .isInstanceOf(AutomaticInputEngine.ExecuteResult.Held::class.java)
        assertThat(exec.dispatched).isEmpty()
    }

    @Test
    fun uncertainMove_failSafeHold() {
        val (eng, exec) = engine()
        val u = goodMove(uncertain = true)
        val result = eng.tryExecute(vision(), u)
        assertThat(result).isInstanceOf(AutomaticInputEngine.ExecuteResult.Held::class.java)
        assertThat((result as AutomaticInputEngine.ExecuteResult.Held).reason)
            .contains("uncertain")
        assertThat(exec.dispatched).isEmpty()
    }

    @Test
    fun inputChannelNotReady_blocked() {
        val (eng, exec) = engine(enabled = true, ready = false)
        val result = eng.tryExecute(vision(), goodMove())
        assertThat(result).isInstanceOf(AutomaticInputEngine.ExecuteResult.Held::class.java)
        assertThat((result as AutomaticInputEngine.ExecuteResult.Held).reason)
            .contains("not ready")
        assertThat(exec.dispatched).isEmpty()
    }

    // ---- coord conversion ----

    @Test
    fun touchCoordinateMapper_usesRecognizedGridCenters_notHardcoded() {
        val mapper = TouchCoordinateMapper(swipeDurationMs = 100L)
        // Offset ROI — proves we are not using (0,0)-origin hardcoded pvp coords.
        val roi = ContentRoi(50, 100, 750, 800)
        val grid = GridGeometry.evenSplit(roi, confidence = 0.99f)
        val gesture = mapper.toGesture(Move(0, 0, 6, 6), grid)
        val a = grid.cellBox(0, 0)
        val b = grid.cellBox(6, 6)
        assertThat(gesture.startX).isWithin(0.01f).of(a.centerX())
        assertThat(gesture.startY).isWithin(0.01f).of(a.centerY())
        assertThat(gesture.endX).isWithin(0.01f).of(b.centerX())
        assertThat(gesture.endY).isWithin(0.01f).of(b.centerY())
        // Centers must lie inside the recognized ROI, not at a magic constant.
        assertThat(gesture.startX).isGreaterThan(roi.left.toFloat())
        assertThat(gesture.startY).isGreaterThan(roi.top.toFloat())
        assertThat(gesture.endX).isLessThan(roi.right.toFloat())
        assertThat(gesture.endY).isLessThan(roi.bottom.toFloat())
        assertThat(gesture.durationMs).isEqualTo(100L)
    }

    // ---- feedback loop ----

    @Test
    fun success_thenVerifyNextFrame_boardChanged_allowsNext() {
        val (eng, exec) = engine()
        val colorsBefore = baseColors()
        val before = vision(colors = colorsBefore, roi = ContentRoi(0, 0, 700, 700))
        val execResult = eng.tryExecute(before, goodMove(Move(0, 2, 1, 2)))
        assertThat(execResult).isInstanceOf(AutomaticInputEngine.ExecuteResult.Executed::class.java)
        val executed = execResult as AutomaticInputEngine.ExecuteResult.Executed
        assertThat(eng.stateMachine().state).isEqualTo(BotLoopState.WAIT_FOR_BOARD)

        val colorsAfter = baseColors()
        colorsAfter[0][0] = TileColor.R
        colorsAfter[0][1] = TileColor.R
        colorsAfter[0][2] = TileColor.R
        val after = vision(colors = colorsAfter, roi = ContentRoi(0, 0, 700, 700))
        assertThat(Board.fromVision(after.board).contentHash())
            .isNotEqualTo(executed.beforeBoardHash)

        val fb = eng.verifyAfterInput(executed.beforeBoardHash, after)
        assertThat(fb).isInstanceOf(AutomaticInputEngine.FeedbackResult.Success::class.java)
        assertThat(eng.stateMachine().state).isEqualTo(BotLoopState.CAPTURE)
        assertThat(exec.dispatched).hasSize(1)
    }

    @Test
    fun failedVerify_boardUnchanged_STOP_noBlindRetry() {
        val (eng, _) = engine()
        val v = vision()
        val execResult = eng.tryExecute(v, goodMove()) as AutomaticInputEngine.ExecuteResult.Executed
        // Same board again → unchanged.
        val fb = eng.verifyAfterInput(execResult.beforeBoardHash, v)
        assertThat(fb).isInstanceOf(AutomaticInputEngine.FeedbackResult.Stopped::class.java)
        val stopped = fb as AutomaticInputEngine.FeedbackResult.Stopped
        assertThat(stopped.reason.lowercase()).contains("unchanged")
        assertThat(eng.stateMachine().isTerminal).isTrue()
        assertThat(eng.stateMachine().state).isEqualTo(BotLoopState.STOP)
    }

    @Test
    fun invalidNewFrame_STOP() {
        val (eng, _) = engine()
        val before = vision()
        val execResult = eng.tryExecute(before, goodMove()) as AutomaticInputEngine.ExecuteResult.Executed
        val bad = vision(
            gate = ValidationResult.Hold("HOLD — blur"),
            boardConf = 0.2f,
            gridConf = 0.2f,
        )
        val fb = eng.verifyAfterInput(execResult.beforeBoardHash, bad)
        assertThat(fb).isInstanceOf(AutomaticInputEngine.FeedbackResult.Stopped::class.java)
        assertThat(eng.stateMachine().state).isEqualTo(BotLoopState.STOP)
        assertThat(eng.stateMachine().lastReason.lowercase()).contains("invalid")
    }

    @Test
    fun unknownAfterInput_failSafeSTOP() {
        val (eng, _) = engine()
        val before = vision()
        val execResult = eng.tryExecute(before, goodMove()) as AutomaticInputEngine.ExecuteResult.Executed
        val colors = baseColors()
        colors[0][0] = TileColor.UNKNOWN
        colors[1][1] = TileColor.UNKNOWN
        val after = vision(colors = colors)
        val fb = eng.verifyAfterInput(execResult.beforeBoardHash, after)
        assertThat(fb).isInstanceOf(AutomaticInputEngine.FeedbackResult.Stopped::class.java)
        assertThat(eng.stateMachine().state).isEqualTo(BotLoopState.STOP)
        assertThat(eng.stateMachine().lastReason.lowercase()).contains("fail-safe")
    }

    @Test
    fun repeatedVerifyFailure_STOP() {
        val sm = BotStateMachine(maxVerifyFailures = 2)
        sm.startCapture()
        sm.onValidationPass()
        sm.onAnalysisReady()
        sm.onMoveSelected()
        sm.onInputExecuted()
        sm.onBoardReadyForVerify()
        val first = sm.onVerifyFailed("unchanged")
        assertThat(first.outcome).isEqualTo(BotLoopOutcome.STOP)
        assertThat(sm.state).isEqualTo(BotLoopState.STOP)
        assertThat(sm.verifyFailureCount).isEqualTo(1)

        // Second failure path after reset simulating another attempt cycle:
        val sm2 = BotStateMachine(maxVerifyFailures = 1)
        sm2.startCapture()
        sm2.onValidationPass()
        sm2.onAnalysisReady()
        sm2.onMoveSelected()
        sm2.onInputExecuted()
        sm2.onBoardReadyForVerify()
        sm2.onVerifyFailed("fail-1")
        // Already STOP; calling again keeps STOP with repeated counter if we reset path:
        val sm3 = BotStateMachine(maxVerifyFailures = 0)
        sm3.startCapture()
        sm3.onValidationPass()
        sm3.onAnalysisReady()
        sm3.onMoveSelected()
        sm3.onInputExecuted()
        sm3.onBoardReadyForVerify()
        val repeated = sm3.onVerifyFailed("again")
        assertThat(repeated.reason.lowercase()).contains("repeated")
        assertThat(sm3.state).isEqualTo(BotLoopState.STOP)
    }

    @Test
    fun shellInputExecutor_buildsSwipeCommand() {
        var last: List<String>? = null
        val shell = ShellInputGestureExecutor { cmd ->
            last = cmd
            0
        }
        val g = GestureSpec(10f, 20f, 30f, 40f, 120L)
        val r = shell.dispatch(g)
        assertThat(r).isInstanceOf(InputDispatchResult.Dispatched::class.java)
        assertThat(last).containsExactly(
            "input", "swipe", "10", "20", "30", "40", "120",
        ).inOrder()
    }
}
