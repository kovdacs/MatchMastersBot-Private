package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import com.match3vision.analyzer.hud.HudObservation
import com.match3vision.analyzer.hud.TurnGate
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
 * The analysis-only loop still never dispatches. A 5-move gesture requires an
 * issued permit, and a failed move-1 callback does not unlock another one.
 */
class FiveMoveDispatchTest {

    @Test
    fun analysisOnly_withA11yOff_neverDispatches_evenAfterArm() {
        val (ctrl, exec) = diagnosticController()
        assertThat(ctrl.armFiveMoveTest(nowMs = 0L, selfCheckThisSession = true)).isTrue()
        assertThat(ctrl.analysisOnly).isTrue()
        assertThat(ctrl.enableSwitch().isEnabled()).isFalse()
        val cycle = ctrl.runCycleIfActive(passVision(), context(a11yConnected = false))
        assertThat(cycle).isNotNull()
        assertThat(cycle!!.gestureStatus).isEqualTo("NOT CREATED")
        assertThat(cycle.executed).isNull()
        assertThat(exec.dispatched).isEmpty()
        assertThat(ctrl.analysisOnly).isTrue()
        val forged = FiveMoveSession.Permit(token = 99L, moveNumber = 1)
        assertThat(ctrl.dispatchFiveMoveOnce(passVision(), context(a11yConnected = true), forged)).isNull()
        assertThat(exec.dispatched).isEmpty()
        assertThat(ctrl.enableSwitch().isEnabled()).isFalse()
    }

    @Test
    fun noPermitWithoutSelfCheck_andContinuousStartStaysSeparate() {
        val (ctrl, exec) = diagnosticController()
        assertThat(ctrl.armFiveMoveTest(nowMs = 0L, selfCheckThisSession = false)).isFalse()
        assertThat(ctrl.lastReason).contains("TESZT ÉRINTÉS")
        assertThat(ctrl.fiveMove.phase).isEqualTo(FiveMoveSession.Phase.IDLE)
        assertThat(exec.dispatched).isEmpty()
        assertThat(ctrl.fiveMove.arm(0L)).isTrue()
        val blocked = ctrl.fiveMove.requestDispatch(
            FiveMoveSession.Gates(
                nowMs = 1_000L,
                a11yConnected = true,
                selfCheckMeasured = false,
                overlayCollapsed = true,
                overlayOutsideRoi = true,
                visionPass = true,
                frameFresh = true,
                ownUi = false,
                msSinceCollapse = 2_000L,
                roiPlausible = true,
            ),
        )
        assertThat(blocked).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(exec.dispatched).isEmpty()
    }

    @Test
    fun unknownHud_andVisionPass_dispatchesAMove() {
        val (ctrl, exec) = controllerWith(CompletingExecutor(complete = true))
        assertThat(ctrl.armFiveMoveTest(0L, selfCheckThisSession = true)).isTrue()
        val hud = HudObservation.UNKNOWN
        assertThat(hud.hudState).isEqualTo("UNKNOWN")
        assertThat(TurnGate.refusal(hud)).isNull()
        ctrl.fiveMove.noteHud(hud.log())
        val go = ctrl.fiveMove.requestDispatch(readyGates(1_000L))
        assertThat(go).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
        val permit = (go as FiveMoveSession.Decision.Go).permit
        val cycle = ctrl.dispatchFiveMoveOnce(passVision(), context(a11yConnected = true), permit, hud)
        val executed = cycle!!.executed as AutomaticInputEngine.ExecuteResult.Executed
        assertThat(executed.verificationEligible).isTrue()
        assertThat(exec.dispatched).hasSize(1)
        assertThat(ctrl.fiveMove.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(ctrl.fiveMove.report()).contains("hudState=UNKNOWN")
    }

    @Test
    fun failedCallback_blocksMoves2To5_andRunCycleStillDoesNotDispatch() {
        val (ctrl, exec) = controllerWith(CompletingExecutor(complete = false))
        assertThat(ctrl.armFiveMoveTest(0L, selfCheckThisSession = true)).isTrue()
        val go = ctrl.fiveMove.requestDispatch(readyGates(1_000L))
        assertThat(go).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
        val permit = (go as FiveMoveSession.Decision.Go).permit
        val cycle = ctrl.dispatchFiveMoveOnce(passVision(), context(a11yConnected = true), permit)
        assertThat(cycle).isNotNull()
        val executed = cycle!!.executed as AutomaticInputEngine.ExecuteResult.Executed
        assertThat(executed.verificationEligible).isFalse()
        assertThat(exec.dispatched).hasSize(1)
        assertThat(ctrl.enableSwitch().isEnabled()).isFalse()
        assertThat(ctrl.analysisOnly).isTrue()
        val noted = ctrl.fiveMove.noteGesture(
            FiveMoveSession.GestureFact(
                startedAtMs = 1_000L,
                nowMs = 1_100L,
                callbackCompleted = executed.verificationEligible,
                cancelled = true,
                cells = executed.move.move.toString(),
                fromX = executed.gesture.startX,
                fromY = executed.gesture.startY,
                toX = executed.gesture.endX,
                toY = executed.gesture.endY,
                beforeHash = executed.beforeBoardHash,
                beforeUnknown = 0,
            ),
        )
        assertThat(noted).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(ctrl.fiveMove.requestDispatch(readyGates(2_000L)))
            .isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        ctrl.runCycleIfActive(passVision(), context(a11yConnected = true))
        assertThat(exec.dispatched).hasSize(1)
        assertThat(ctrl.fiveMove.verifiedCount).isEqualTo(0)
        assertThat(ctrl.fiveMove.gesturesDispatched).isEqualTo(1)
    }

    @Test
    fun fivePermittedGestures_thenTheSessionStops() {
        val (ctrl, exec) = controllerWith(CompletingExecutor(complete = true))
        assertThat(ctrl.armFiveMoveTest(0L, selfCheckThisSession = true)).isTrue()
        repeat(FiveMoveSession.MAX_MOVES) { index ->
            val start = 1_000L + index * 1_000L
            if (index > 0) {
                val settled = 50L + index - 1
                ctrl.fiveMove.noteFreshBoard(settled, fresh = true, pass = true)
                ctrl.fiveMove.noteFreshBoard(settled, fresh = true, pass = true)
            }
            val go = ctrl.fiveMove.requestDispatch(readyGates(start))
            assertThat(go).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
            val permit = (go as FiveMoveSession.Decision.Go).permit
            val cycle = ctrl.dispatchFiveMoveOnce(passVision(), context(true), permit)
            val executed = cycle!!.executed as AutomaticInputEngine.ExecuteResult.Executed
            assertThat(executed.verificationEligible).isTrue()
            ctrl.fiveMove.noteGesture(
                FiveMoveSession.GestureFact(
                    startedAtMs = start,
                    nowMs = start + 50L,
                    callbackCompleted = true,
                    cancelled = false,
                    cells = executed.move.move.toString(),
                    fromX = executed.gesture.startX,
                    fromY = executed.gesture.startY,
                    toX = executed.gesture.endX,
                    toY = executed.gesture.endY,
                    beforeHash = index.toLong(),
                    beforeUnknown = 0,
                ),
            )
            val hash = 50L + index
            ctrl.fiveMove.onSettle(
                FiveMoveSession.SettleSample(
                    nowMs = start,
                    boardHash = hash,
                    diffFraction = 0f,
                    frameFresh = true,
                    roiPlausible = true,
                    visionPass = true,
                    unknownCount = 0,
                    ownUi = false,
                    a11yConnected = true,
                ),
            )
            ctrl.fiveMove.onSettle(
                FiveMoveSession.SettleSample(
                    nowMs = start + 300L,
                    boardHash = hash,
                    diffFraction = 0f,
                    frameFresh = true,
                    roiPlausible = true,
                    visionPass = true,
                    unknownCount = 0,
                    ownUi = false,
                    a11yConnected = true,
                ),
            )
            ctrl.fiveMove.onSettle(
                FiveMoveSession.SettleSample(
                    nowMs = start + 600L,
                    boardHash = hash,
                    diffFraction = 0f,
                    frameFresh = true,
                    roiPlausible = true,
                    visionPass = true,
                    unknownCount = 0,
                    ownUi = false,
                    a11yConnected = true,
                ),
            )
        }
        assertThat(exec.dispatched).hasSize(FiveMoveSession.MAX_MOVES)
        assertThat(ctrl.fiveMove.verifiedCount).isEqualTo(FiveMoveSession.MAX_MOVES)
        assertThat(ctrl.fiveMove.phase).isEqualTo(FiveMoveSession.Phase.STOPPED)
        assertThat(ctrl.enableSwitch().isEnabled()).isFalse()
        assertThat(ctrl.analysisOnly).isTrue()
        ctrl.runCycleIfActive(passVision(), context(true))
        assertThat(exec.dispatched).hasSize(FiveMoveSession.MAX_MOVES)
        val late = ctrl.fiveMove.requestDispatch(readyGates(90_000L))
        assertThat(late).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(
            ctrl.dispatchFiveMoveOnce(
                passVision(),
                context(true),
                FiveMoveSession.Permit(token = 99L, moveNumber = FiveMoveSession.MAX_MOVES + 1),
            ),
        ).isNull()
        assertThat(exec.dispatched).hasSize(FiveMoveSession.MAX_MOVES)
        val report = ctrl.fiveMove.report()
        assertThat(report).contains("measuredSessionMs=")
        assertThat(report).contains("durationMs=600")
    }

    @Test
    fun endedSession_neverDispatchesAQueuedPermit() {
        val cap = controllerWith(CompletingExecutor(complete = true))
        val (capCtrl, capExec) = cap
        assertThat(capCtrl.armFiveMoveTest(0L, selfCheckThisSession = true)).isTrue()
        val capGo = capCtrl.fiveMove.requestDispatch(readyGates(1_000L)) as FiveMoveSession.Decision.Go
        assertThat(
            capCtrl.fiveMove.pollSafety(
                nowMs = FiveMoveSession.SESSION_LIMIT_MS,
                ownUi = false,
                a11yConnected = true,
            ),
        ).isNotNull()
        assertThat(capCtrl.fiveMove.phase).isEqualTo(FiveMoveSession.Phase.STOPPED)
        assertThat(capCtrl.dispatchFiveMoveOnce(passVision(), context(true), capGo.permit)).isNull()
        assertThat(capCtrl.fiveMove.requestDispatch(readyGates(FiveMoveSession.SESSION_LIMIT_MS + 1_000L)))
            .isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(capExec.dispatched).isEmpty()

        val settle = controllerWith(CompletingExecutor(complete = true))
        val (settleCtrl, settleExec) = settle
        assertThat(settleCtrl.armFiveMoveTest(0L, selfCheckThisSession = true)).isTrue()
        val first = settleCtrl.fiveMove.requestDispatch(readyGates(1_000L)) as FiveMoveSession.Decision.Go
        val cycle = settleCtrl.dispatchFiveMoveOnce(passVision(), context(true), first.permit)
        val executed = cycle!!.executed as AutomaticInputEngine.ExecuteResult.Executed
        settleCtrl.fiveMove.noteGesture(
            FiveMoveSession.GestureFact(
                startedAtMs = 1_000L,
                nowMs = 1_100L,
                callbackCompleted = true,
                cancelled = false,
                cells = executed.move.move.toString(),
                fromX = executed.gesture.startX,
                fromY = executed.gesture.startY,
                toX = executed.gesture.endX,
                toY = executed.gesture.endY,
                beforeHash = executed.beforeBoardHash,
                beforeUnknown = 0,
            ),
        )
        assertThat(
            settleCtrl.fiveMove.onSettle(
                FiveMoveSession.SettleSample(
                    nowMs = 2_000L,
                    boardHash = executed.beforeBoardHash,
                    diffFraction = 0.98f,
                    frameFresh = true,
                    roiPlausible = true,
                    visionPass = false,
                    unknownCount = 24,
                    ownUi = false,
                    a11yConnected = true,
                ),
            ),
        ).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(settleCtrl.fiveMove.requestDispatch(readyGates(2_100L)))
            .isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        val failed = settleCtrl.fiveMove.onSettle(
            FiveMoveSession.SettleSample(
                nowMs = 1_000L + FiveMoveSession.PER_MOVE_BUDGET_MS,
                boardHash = executed.beforeBoardHash + 1,
                diffFraction = 0.16f,
                frameFresh = true,
                roiPlausible = true,
                visionPass = false,
                unknownCount = 2,
                ownUi = false,
                a11yConnected = true,
            ),
        )
        assertThat(failed).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(settleCtrl.fiveMove.movesSnapshot().single().verification).contains("CHANGED_UNSETTLED")
        assertThat(settleCtrl.fiveMove.verifiedCount).isEqualTo(0)
        settleCtrl.runCycleIfActive(passVision(), context(true))
        assertThat(settleCtrl.fiveMove.requestDispatch(readyGates(30_000L)))
            .isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(
            settleCtrl.dispatchFiveMoveOnce(
                passVision(),
                context(true),
                FiveMoveSession.Permit(token = 50L, moveNumber = 2),
            ),
        ).isNull()
        assertThat(settleExec.dispatched).hasSize(1)

        val stopped = controllerWith(CompletingExecutor(complete = true))
        val (stopCtrl, stopExec) = stopped
        assertThat(stopCtrl.armFiveMoveTest(0L, selfCheckThisSession = true)).isTrue()
        val queued = stopCtrl.fiveMove.requestDispatch(readyGates(500L)) as FiveMoveSession.Decision.Go
        stopCtrl.onBubbleStop("felhasználó STOP")
        assertThat(stopCtrl.mode).isEqualTo(AutoPlayController.Mode.STOPPED)
        assertThat(stopCtrl.fiveMove.phase).isEqualTo(FiveMoveSession.Phase.STOPPED)
        assertThat(stopCtrl.dispatchFiveMoveOnce(passVision(), context(true), queued.permit)).isNull()
        assertThat(stopCtrl.fiveMove.requestDispatch(readyGates(2_000L)))
            .isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(stopExec.dispatched).isEmpty()
    }

    private fun readyGates(nowMs: Long) = FiveMoveSession.Gates(
        nowMs = nowMs,
        a11yConnected = true,
        selfCheckMeasured = true,
        overlayCollapsed = true,
        overlayOutsideRoi = true,
        visionPass = true,
        frameFresh = true,
        ownUi = false,
        msSinceCollapse = 2_000L,
        roiPlausible = true,
    )

    private fun diagnosticController(): Pair<AutoPlayController, CompletingExecutor> =
        controllerWith(CompletingExecutor(complete = false))

    private fun controllerWith(
        exec: CompletingExecutor,
    ): Pair<AutoPlayController, CompletingExecutor> {
        CoordinateSelfCheck.clear()
        val sw = InputEnableSwitch.disabledByDefault()
        val ctrl = AutoPlayController(
            enableSwitch = sw,
            inputLoop = InputLoopController(
                inputEngine = AutomaticInputEngine(enableSwitch = sw, executor = exec),
            ),
        )
        assertThat(ctrl.onDiagnosticStart()).isTrue()
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

    private class CompletingExecutor(
        private val complete: Boolean,
    ) : InputGestureExecutor {
        val dispatched: MutableList<GestureSpec> = mutableListOf()

        override fun isReady(): Boolean = true

        override fun dispatch(gesture: GestureSpec): InputDispatchResult {
            dispatched += gesture
            return InputDispatchResult.Dispatched(gesture, callbackCompleted = complete)
        }
    }
}
