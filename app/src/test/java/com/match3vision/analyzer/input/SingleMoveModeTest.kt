package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
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

class SingleMoveModeTest {

    @Test
    fun stateMachine_oneArm_thenPauseVerifyStop_secondDispatchRefused() {
        val machine = SingleMoveMachine()
        assertThat(machine.arm()).isTrue()
        assertThat(machine.label()).isEqualTo("ARMED")
        machine.onObserve()
        assertThat(machine.label()).isEqualTo("ANALYZING")
        assertThat(machine.tryProductionDispatch()).isTrue()
        assertThat(machine.label()).isEqualTo("ONE MOVE")
        assertThat(machine.productionDispatches).isEqualTo(1)
        assertThat(machine.tryProductionDispatch()).isFalse()
        assertThat(machine.productionDispatches).isEqualTo(1)
        machine.beginVerify()
        assertThat(machine.label()).isEqualTo("VERIFYING")
        machine.onVerified()
        assertThat(machine.label()).isEqualTo("PAUSED")
        machine.finishStopped()
        assertThat(machine.label()).isEqualTo("STOPPED")
        assertThat(machine.allowsProductionDispatch()).isFalse()
        assertThat(machine.arm()).isTrue()
        assertThat(machine.productionDispatches).isEqualTo(0)
        assertThat(machine.label()).isEqualTo("ARMED")
    }

    @Test
    fun oneArm_oneProductionDispatch_holdDoesNotConsumeIt_unconfirmedDoesNotRepeat() {
        val exec = RecordingInputGestureExecutor(ready = true)
        val sw = InputEnableSwitch.disabledByDefault()
        val ctrl = AutoPlayController(
            enableSwitch = sw,
            inputLoop = InputLoopController(
                inputEngine = AutomaticInputEngine(enableSwitch = sw, executor = exec),
            ),
        )
        assertThat(ctrl.armSingleMove()).isTrue()
        assertThat(ctrl.singleMove.label()).isEqualTo("ARMED")
        val held = ctrl.runCycleIfActive(vision(seed = 0, pass = false))!!
        assertThat(held.outcome).isEqualTo(BotLoopOutcome.HOLD)
        assertThat(exec.dispatched).isEmpty()
        assertThat(ctrl.singleMove.allowsProductionDispatch()).isTrue()
        assertThat(ctrl.singleMove.label()).isEqualTo("ANALYZING")
        val cycle = ctrl.runCycleIfActive(vision(seed = 1, pass = true))!!
        assertThat(cycle.executed).isInstanceOf(AutomaticInputEngine.ExecuteResult.Executed::class.java)
        assertThat(exec.dispatched).hasSize(1)
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.PAUSED)
        assertThat(ctrl.singleMove.label()).isEqualTo("ONE MOVE")
        assertThat(ctrl.singleMove.productionDispatches).isEqualTo(1)
        assertThat(ctrl.runCycleIfActive(vision(seed = 2, pass = true))).isNull()
        assertThat(exec.dispatched).hasSize(1)
        val executed = cycle.executed as AutomaticInputEngine.ExecuteResult.Executed
        val before = vision(seed = 1, pass = true)
        val after = swapIntendedCells(before, executed.move.move)
        assertThat(Board.fromVision(after.board).contentHash()).isNotEqualTo(executed.beforeBoardHash)
        assertThat(SwapRegionCheck.intendedCellsChanged(before, after, executed.move.move)).isTrue()
        val fb = ctrl.completeFeedback(
            executed.beforeBoardHash,
            after,
            VerifyObservation(
                newFrameAccepted = true,
                frameFresh = true,
                gestureEligible = true,
                frameElapsedMs = 8_000L,
                dispatchCompletedElapsedMs = 7_000L,
                preDispatchSequence = 1L,
                afterSequence = 2L,
            ),
            beforeVision = before,
            attemptedMove = executed.move.move,
        )!!
        assertThat(fb.verifyStatus).isEqualTo(VerificationPolicy.BOARD_CHANGED_UNCONFIRMED)
        assertThat(fb.verifyStatus).isNotEqualTo(VerificationPolicy.SUCCESS)
        assertThat(ctrl.singleMove.label()).isEqualTo("PAUSED")
        ctrl.finishSingleMoveAfterExport()
        assertThat(ctrl.singleMove.label()).isEqualTo("STOPPED")
        assertThat(ctrl.runCycleIfActive(vision(seed = 3, pass = true))).isNull()
        assertThat(exec.dispatched).hasSize(1)
        assertThat(cycle.callbackOutcome)
            .isEqualTo("callback not completed — not eligible for verification")
        assertThat(cycle.callbackOutcome).doesNotContain("VERIFY SUCCESS")
        assertThat(cycle.callbackOutcome).isNotEqualTo(cycle.lastDispatch.name)
    }

    @Test
    fun manualTouchPath_doesNotCountAsProductionDispatch() {
        val machine = SingleMoveMachine()
        val channel = object : AccessibilityGestureChannel {
            var calls = 0
            override fun canDispatchGestures(): Boolean = true
            override fun diagnose(): String = "manual-test"
            override fun dispatchGesture(
                gesture: GestureSpec,
                awaitCompletion: Boolean,
                timeoutMs: Long,
            ): InputDispatchResult {
                calls += 1
                return InputDispatchResult.Dispatched(gesture, callbackCompleted = true)
            }
        }
        val executor = AccessibilityGestureExecutor(
            serviceProvider = { channel },
            nowElapsedMs = { 1_000L },
            liveProbe = LiveDispatchProbe.unavailable(),
        )
        val manual = executor.dispatchManualTest(GestureSpec(10f, 10f, 40f, 10f, 120L))
        assertThat(manual).isInstanceOf(InputDispatchResult.Dispatched::class.java)
        assertThat(channel.calls).isEqualTo(1)
        assertThat(machine.productionDispatches).isEqualTo(0)
        assertThat(machine.phase).isEqualTo(SingleMoveMachine.Phase.IDLE)
        val unguarded = executor.dispatch(GestureSpec(10f, 10f, 40f, 10f, 120L))
        assertThat(unguarded).isInstanceOf(InputDispatchResult.Failed::class.java)
        assertThat((unguarded as InputDispatchResult.Failed).reason).contains("TESZT")
        assertThat(channel.calls).isEqualTo(1)
        assertThat(GestureCallbackPolicy.decide(true, true, true, true, false).verifyLabel())
            .isEqualTo(VerificationPolicy.PENDING)
        assertThat(GestureCallbackPolicy.decide(true, true, true, true, false).verifyLabel())
            .isNotEqualTo(VerificationPolicy.SUCCESS)
    }

    @Test
    fun productionChannel_singleMove_callsDispatchCheckedOnce() {
        val channel = object : AccessibilityGestureChannel {
            var calls = 0
            override fun canDispatchGestures(): Boolean = true
            override fun diagnose(): String = "counting"
            override fun dispatchGesture(
                gesture: GestureSpec,
                awaitCompletion: Boolean,
                timeoutMs: Long,
            ): InputDispatchResult {
                calls += 1
                return InputDispatchResult.Dispatched(gesture, callbackCompleted = true)
            }
        }
        val screen = ScreenMeasurement(
            widthPx = 700,
            heightPx = 700,
            rotation = 0,
            source = ScreenMeasurement.SOURCE_MAXIMUM_WINDOW,
        )
        val executor = ProductionInstall.accessibilityExecutor(
            serviceProvider = { channel },
            nowElapsedMs = { 10_100L },
            liveProbe = LiveDispatchProbe(
                inputEnabled = { true },
                stopped = { false },
                captureReady = { true },
                screen = { screen },
            ),
        )
        val sw = InputEnableSwitch.disabledByDefault()
        val ctrl = AutoPlayController(
            enableSwitch = sw,
            inputLoop = InputLoopController(
                inputEngine = AutomaticInputEngine(enableSwitch = sw, executor = executor),
            ),
        )
        assertThat(ctrl.armSingleMove()).isTrue()
        val ctx = ProductionCycleContext.fromLoopObservation(
            a11yConnected = true,
            captureManagerPresent = true,
            hasFrame = true,
            frameAgeMs = 20L,
            frameSequenceDecision = null,
            frameTimestampMs = 5_000L,
            frameWidth = 700,
            frameHeight = 700,
            capturedElapsedMs = 10_000L,
            screen = screen,
            frameSequence = 1L,
        )
        val cycle = ctrl.runCycleIfActive(vision(seed = 1, pass = true), ctx)
        assertThat(cycle!!.executed).isInstanceOf(AutomaticInputEngine.ExecuteResult.Executed::class.java)
        assertThat(channel.calls).isEqualTo(1)
        assertThat(ctrl.runCycleIfActive(vision(seed = 2, pass = true), ctx)).isNull()
        assertThat(channel.calls).isEqualTo(1)
        assertThat(ctrl.singleMove.productionDispatches).isEqualTo(1)
        assertThat(cycle.callbackOutcome).contains("not VERIFY SUCCESS")
        assertThat(cycle.callbackOutcome).isNotEqualTo(cycle.lastDispatch.name)
    }

    private fun swapIntendedCells(before: VisionResult, move: com.match3vision.analyzer.moves.Move): VisionResult {
        val cells = Array(7) { r -> Array(7) { c -> before.board.get(r, c) } }
        val a = cells[move.r1][move.c1]
        val b = cells[move.r2][move.c2]
        cells[move.r1][move.c1] = b
        cells[move.r2][move.c2] = a
        if (a.color == b.color && a.shape == b.shape && a.special == b.special) {
            cells[move.r1][move.c1] = a.copy(color = TileColor.G)
        }
        return before.copy(board = VisionBoard(cells))
    }

    private fun vision(seed: Int, pass: Boolean): VisionResult {
        val palette = listOf(
            TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O, TileColor.R,
        )
        val colors = Array(7) { r -> Array(7) { c -> palette[(r * 3 + c * 2 + seed) % 6] } }
        colors[0][0] = TileColor.R
        colors[0][1] = TileColor.R
        colors[0][2] = TileColor.B
        colors[0][3] = TileColor.Y
        colors[1][2] = TileColor.R
        colors[6][6] = palette[seed % 6]
        val cells = Array(7) { r -> Array(7) { c ->
            CellVision(
                color = colors[r][c],
                shape = TileShape.CIRCLE,
                special = SpecialType.NONE,
                occluded = false,
                confidence = 1f,
                isUnknown = false,
            )
        } }
        return VisionResult(
            board = VisionBoard(cells),
            grid = GridGeometry.evenSplit(ContentRoi(0, 0, 700, 700), 0.99f),
            unknownCount = 0,
            confidence = 1f,
            boardConfidence = VisionThresholds.MIN_BOARD_CONFIDENCE,
            gridConfidence = VisionThresholds.MIN_GRID_CONFIDENCE,
            validation = if (pass) {
                ValidationResult.Pass
            } else {
                ValidationResult.Hold("HOLD: unknownCount=5")
            },
            method = GridMethod.EVEN_SPLIT,
        )
    }
}
