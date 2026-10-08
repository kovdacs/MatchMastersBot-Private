package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import com.match3vision.analyzer.capture.ScreenMeasurement
import com.match3vision.analyzer.vision.GridMethod
import com.match3vision.analyzer.vision.RealFrameLoader
import com.match3vision.analyzer.vision.ValidationResult
import com.match3vision.analyzer.vision.VisionPipeline
import com.match3vision.analyzer.vision.VisionThresholds
import org.junit.Test

/**
 * Checked-in pvp_board.jpg pixels through the production classes.
 *
 * CountingChannel replaces only the framework dispatchGesture call.
 * This is a simulation. It is not a phone touch.
 */
class FixtureToGestureEndToEndTest {

    private class CountingChannel : AccessibilityGestureChannel {
        var dispatchGestureCalls: Int = 0
        var lastGesture: GestureSpec? = null
        override fun canDispatchGestures(): Boolean = true
        override fun diagnose(): String = "counting-channel"
        override fun dispatchGesture(
            gesture: GestureSpec,
            awaitCompletion: Boolean,
            timeoutMs: Long,
        ): InputDispatchResult {
            dispatchGestureCalls += 1
            lastGesture = gesture
            return InputDispatchResult.Dispatched(gesture, callbackCompleted = true)
        }
    }

    @Test
    fun pvpBoardPixels_visionPass_move_productionDispatch_projectionCenters() {
        val frame = RealFrameLoader.loadFromResource()
            ?: error("STAGE fixture-load: ${RealFrameLoader.PVP_BOARD_RESOURCE} missing")
        val vision = VisionPipeline().analyze(
            frame.pixels,
            frame.width,
            frame.height,
            ContentRoi.full(frame.width, frame.height),
        )
        println(
            "E2E_FIXTURE SIMULATION gate=${if (vision.validation.isPass) "PASS" else "HOLD"} " +
                "method=${vision.method} grid=${vision.gridConfidence} " +
                "board=${vision.boardConfidence} unk=${vision.unknownCount} " +
                "frame=${frame.width}x${frame.height}",
        )
        if (!vision.validation.isPass) {
            val reason = (vision.validation as? ValidationResult.Hold)?.reason ?: "HOLD"
            error(
                "STAGE vision: fixture did not PASS ($reason) " +
                    "grid=${vision.gridConfidence} board=${vision.boardConfidence} " +
                    "unk=${vision.unknownCount}. This test does not manufacture a PASS.",
            )
        }
        assertThat(vision.gridConfidence).isAtLeast(VisionThresholds.MIN_GRID_CONFIDENCE)
        assertThat(vision.boardConfidence).isAtLeast(VisionThresholds.MIN_BOARD_CONFIDENCE)
        assertThat(vision.unknownCount).isAtMost(VisionThresholds.MAX_UNKNOWN_COUNT)
        if (vision.method != GridMethod.PROJECTION) {
            error(
                "STAGE geometry: expected PROJECTION, got ${vision.method}. " +
                    "EVEN_SPLIT is not accepted as the coordinate evidence.",
            )
        }

        val channel = CountingChannel()
        val screen = ScreenMeasurement(
            widthPx = frame.width,
            heightPx = frame.height,
            densityDpi = 420,
            rotation = 0,
            source = ScreenMeasurement.SOURCE_MAXIMUM_WINDOW,
        )
        val executor = ProductionInstall.accessibilityExecutor(
            serviceProvider = { channel },
            nowElapsedMs = { 50_100L },
            liveProbe = LiveDispatchProbe(
                inputEnabled = { true },
                stopped = { false },
                captureReady = { true },
                screen = { screen },
            ),
        )
        assertThat(executor).isInstanceOf(AccessibilityGestureExecutor::class.java)
        assertThat(executor).isNotInstanceOf(RecordingInputGestureExecutor::class.java)
        val sw = InputEnableSwitch.disabledByDefault()
        val ctrl = AutoPlayController(
            enableSwitch = sw,
            inputLoop = InputLoopController(
                inputEngine = AutomaticInputEngine(enableSwitch = sw, executor = executor),
            ),
        )
        assertThat(
            ctrl.onStartRequested(a11yConnected = true, captureReady = true, overlayReady = true),
        ).isTrue()
        val ctx = ProductionCycleContext.fromLoopObservation(
            a11yConnected = true,
            captureManagerPresent = true,
            hasFrame = true,
            frameAgeMs = 40L,
            frameSequenceDecision = null,
            frameTimestampMs = 1_700_000_000_000L,
            frameWidth = frame.width,
            frameHeight = frame.height,
            capturedElapsedMs = 50_000L,
            screen = screen,
            frameSequence = 1L,
        )
        assertThat(ctx.screenSource).isEqualTo(ScreenMeasurement.SOURCE_MAXIMUM_WINDOW)
        assertThat(ctx.coordinateAlignmentProven).isFalse()
        val cycle = ctrl.runCycleIfActive(vision, ctx)
            ?: error("STAGE controller: runCycleIfActive returned null")
        if (cycle.executed !is AutomaticInputEngine.ExecuteResult.Executed) {
            error(
                "STAGE move-or-dispatch: ${cycle.outcome} ${cycle.reason} " +
                    "gesture=${cycle.gestureStatus}",
            )
        }
        val executed = cycle.executed as AutomaticInputEngine.ExecuteResult.Executed
        assertThat(channel.dispatchGestureCalls).isEqualTo(1)
        val gesture = channel.lastGesture ?: error("STAGE dispatch: channel was not called")
        assertThat(gesture).isEqualTo(executed.gesture)
        val move = executed.move.move
        val grid = vision.grid
        val handStartX = HandMeasuredPvpCenters.columnCenterX[move.c1]
        val handStartY = HandMeasuredPvpCenters.rowCenterY[move.r1]
        val handEndX = HandMeasuredPvpCenters.columnCenterX[move.c2]
        val handEndY = HandMeasuredPvpCenters.rowCenterY[move.r2]
        assertThat(gesture.startX).isWithin(HandMeasuredPvpCenters.TOLERANCE_PX).of(handStartX)
        assertThat(gesture.startY).isWithin(HandMeasuredPvpCenters.TOLERANCE_PX).of(handStartY)
        assertThat(gesture.endX).isWithin(HandMeasuredPvpCenters.TOLERANCE_PX).of(handEndX)
        assertThat(gesture.endY).isWithin(HandMeasuredPvpCenters.TOLERANCE_PX).of(handEndY)
        var maxDx = 0f
        var maxDy = 0f
        for (r in 0 until 7) {
            for (c in 0 until 7) {
                val box = grid.cellBox(r, c)
                val dx = kotlin.math.abs(box.centerX() - HandMeasuredPvpCenters.columnCenterX[c])
                val dy = kotlin.math.abs(box.centerY() - HandMeasuredPvpCenters.rowCenterY[r])
                if (dx > maxDx) maxDx = dx
                if (dy > maxDy) maxDy = dy
                assertThat(box.centerX())
                    .isWithin(HandMeasuredPvpCenters.TOLERANCE_PX)
                    .of(HandMeasuredPvpCenters.columnCenterX[c])
                assertThat(box.centerY())
                    .isWithin(HandMeasuredPvpCenters.TOLERANCE_PX)
                    .of(HandMeasuredPvpCenters.rowCenterY[r])
            }
        }
        assertThat(gesture.durationMs).isEqualTo(InputThresholds.SWIPE_DURATION_MS)
        assertThat(cycle.verifyStatus).isEqualTo(VerificationPolicy.PENDING)
        assertThat(cycle.verifyStatus).isNotEqualTo(VerificationPolicy.SUCCESS)
        val row2Detected = grid.cellBox(2, 0).centerY()
        val row2Err = kotlin.math.abs(row2Detected - HandMeasuredPvpCenters.rowCenterY[2])
        println(
            "ORACLE row2 hand=${HandMeasuredPvpCenters.rowCenterY[2]} detected=$row2Detected " +
                "absErr=$row2Err tolerance=${HandMeasuredPvpCenters.TOLERANCE_PX} " +
                "(min column pitch / 5, not this error) row6Hand=${HandMeasuredPvpCenters.rowCenterY[6]}",
        )
        println(
            "E2E_FIXTURE SIMULATION move=${move.r1},${move.c1}->${move.r2},${move.c2} " +
                "hand=(${handStartX},${handStartY})->(${handEndX},${handEndY}) " +
                "actual=(${gesture.startX},${gesture.startY})->(${gesture.endX},${gesture.endY}) " +
                "tolerancePx=${HandMeasuredPvpCenters.TOLERANCE_PX} " +
                "maxAbsDx=$maxDx maxAbsDy=$maxDy " +
                "channelCalls=${channel.dispatchGestureCalls} " +
                "alignmentProven=${ctx.coordinateAlignmentProven}",
        )
        assertThat(SimulationMarker.BANNER).contains("SIMULATION")
        assertThat(SimulationMarker.BANNER).contains("LIVE PHONE: NOT TESTED")
        assertThat(SimulationMarker.BANNER).contains("FIRST REAL AUTOMATIC TOUCH: NOT PROVEN")
    }

    @Test
    fun visionHold_doesNotReachDispatchChannel() {
        val frame = RealFrameLoader.loadFromResource()
            ?: error("STAGE fixture-load: missing")
        val vision = VisionPipeline().analyze(
            frame.pixels,
            frame.width,
            frame.height,
            ContentRoi.full(frame.width, frame.height),
        )
        val held = vision.copy(
            validation = ValidationResult.Hold("HOLD — forced for negative integration"),
        )
        val channel = CountingChannel()
        val screen = ScreenMeasurement(
            widthPx = frame.width,
            heightPx = frame.height,
            source = ScreenMeasurement.SOURCE_MAXIMUM_WINDOW,
        )
        val ctrl = controller(channel, screen)
        val ctx = ProductionCycleContext.fromLoopObservation(
            a11yConnected = true,
            captureManagerPresent = true,
            hasFrame = true,
            frameAgeMs = 20L,
            frameSequenceDecision = null,
            frameTimestampMs = 2L,
            frameWidth = frame.width,
            frameHeight = frame.height,
            capturedElapsedMs = 50_000L,
            screen = screen,
        )
        val cycle = ctrl.runCycleIfActive(held, ctx)
        assertThat(channel.dispatchGestureCalls).isEqualTo(0)
        assertThat(cycle!!.visionGate).contains("HOLD")
        assertThat(cycle.gestureStatus).isEqualTo("NOT CREATED")
        assertThat(cycle.verifyStatus).isNotEqualTo(VerificationPolicy.SUCCESS)
        assertThat(SimulationMarker.BANNER).contains("SIMULATION")
    }

    @Test
    fun invalidScreenMeasurement_doesNotReachDispatchChannel() {
        val frame = RealFrameLoader.loadFromResource()
            ?: error("STAGE fixture-load: missing")
        val vision = VisionPipeline().analyze(
            frame.pixels,
            frame.width,
            frame.height,
            ContentRoi.full(frame.width, frame.height),
        )
        if (!vision.validation.isPass) {
            error("STAGE vision: cannot exercise the coordinate refusal without a real PASS")
        }
        val channel = CountingChannel()
        val screen = ScreenMeasurement.unavailable()
        val ctrl = controller(channel, screen)
        val ctx = ProductionCycleContext.fromLoopObservation(
            a11yConnected = true,
            captureManagerPresent = true,
            hasFrame = true,
            frameAgeMs = 20L,
            frameSequenceDecision = null,
            frameTimestampMs = 2L,
            frameWidth = frame.width,
            frameHeight = frame.height,
            capturedElapsedMs = 50_000L,
            screen = screen,
        )
        val cycle = ctrl.runCycleIfActive(vision, ctx)
        assertThat(channel.dispatchGestureCalls).isEqualTo(0)
        assertThat(cycle!!.coordinateBlocked || cycle.gestureStatus == "NOT CREATED").isTrue()
        assertThat(cycle.verifyStatus).isNotEqualTo(VerificationPolicy.SUCCESS)
    }

    private fun controller(channel: CountingChannel, screen: ScreenMeasurement): AutoPlayController {
        val executor = ProductionInstall.accessibilityExecutor(
            serviceProvider = { channel },
            nowElapsedMs = { 50_100L },
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
        check(ctrl.onStartRequested(a11yConnected = true, captureReady = true, overlayReady = true))
        return ctrl
    }
}
