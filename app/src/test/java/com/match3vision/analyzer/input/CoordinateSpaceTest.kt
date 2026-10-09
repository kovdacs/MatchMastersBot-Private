package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import com.match3vision.analyzer.capture.ScreenMeasurement
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
 * Coordinate space. Alignment is not claimed.
 * CountingChannel stays at 0 when a detectable mismatch is refused.
 */
class CoordinateSpaceTest {

    private class CountingChannel : AccessibilityGestureChannel {
        var calls: Int = 0
        override fun canDispatchGestures(): Boolean = true
        override fun diagnose(): String = "counting-channel"
        override fun dispatchGesture(
            gesture: GestureSpec,
            awaitCompletion: Boolean,
            timeoutMs: Long,
        ): InputDispatchResult {
            calls += 1
            return InputDispatchResult.Dispatched(gesture, callbackCompleted = true)
        }
    }

    @Test
    fun evenSplitCentres_useFullFramePixels_whenRoiIsNotAtOrigin() {
        val left = 20
        val top = 1206
        val right = 1060
        val bottom = 2246
        val roi = ContentRoi(left, top, right, bottom)
        val grid = GridGeometry.evenSplit(roi, 0.99f)
        val cellW = (right - left).toFloat() / 7f
        val cellH = (bottom - top).toFloat() / 7f
        fun expectedX(col: Int) = left + (col + 0.5f) * cellW
        fun expectedY(row: Int) = top + (row + 0.5f) * cellH
        val gesture = TouchCoordinateMapper().toGesture(Move(2, 3, 3, 3), grid)
        assertThat(gesture.startX).isWithin(0.001f).of(expectedX(3))
        assertThat(gesture.startY).isWithin(0.001f).of(expectedY(2))
        assertThat(gesture.endX).isWithin(0.001f).of(expectedX(3))
        assertThat(gesture.endY).isWithin(0.001f).of(expectedY(3))
        assertThat(gesture.startX).isGreaterThan(20f)
        assertThat(gesture.startY).isGreaterThan(1206f)
        assertThat(CoordinateSpace.VISION_OUTPUT).isEqualTo("full-frame pixels")
        assertThat(CoordinateSpace.ALIGNMENT_PROVEN).isFalse()
        assertThat(GridOriginPolicy.refusal(grid, 1080, 2400)).isNull()
    }

    @Test
    fun originOffset_isRefused_andNotAddedToTheGesture() {
        val (ctrl, channel) = running()
        val ctx = context().copy(originOffsetX = 0, originOffsetY = 80)
        val cycle = ctrl.runCycleIfActive(passVision(), ctx)!!
        assertThat(channel.calls).isEqualTo(0)
        assertThat(cycle.outcome).isEqualTo(BotLoopOutcome.STOP)
        assertThat(cycle.reason).contains("refusing to shift")
        assertThat(cycle.reason).contains("offset not applied")
        val match = Regex("unshifted start=\\(([^,]+),([^)]+)\\)").find(cycle.reason)
        assertThat(match).isNotNull()
        val startY = match!!.groupValues[2].toFloat()
        val shifted = startY + 80f
        assertThat(cycle.reason).doesNotContain("($shifted)")
        assertThat(cycle.reason).contains("originOffset=(0,80)")
    }

    @Test
    fun rotation90_isRefused() {
        val (ctrl, channel) = running()
        val cycle = ctrl.runCycleIfActive(passVision(), context().copy(screenRotation = 1))!!
        assertThat(channel.calls).isEqualTo(0)
        assertThat(cycle.reason).contains("rotation 1 refused")
    }

    @Test
    fun gridOriginMismatch_isRefused() {
        val (ctrl, channel) = running()
        val roi = ContentRoi(20, 1206, 1060, 2246)
        val x = FloatArray(8) { i -> i * 1040f / 7f }
        val y = FloatArray(8) { i -> 1206f + i * 1040f / 7f }
        val grid = GridGeometry(x, y, GridMethod.EVEN_SPLIT, 0.99f, roi)
        assertThat(GridOriginPolicy.refusal(grid, 1080, 2400)).contains("silent shift")
        val cycle = ctrl.runCycleIfActive(passVision(grid), context(1080, 2400))!!
        assertThat(channel.calls).isEqualTo(0)
        assertThat(cycle.reason).contains("refusing a silent shift")
    }

    @Test
    fun roiOutsideFrame_isRefused() {
        val (ctrl, channel) = running()
        val grid = GridGeometry.evenSplit(ContentRoi(0, 0, 800, 2500), 0.99f)
        val cycle = ctrl.runCycleIfActive(passVision(grid), context(700, 700))!!
        assertThat(channel.calls).isEqualTo(0)
        assertThat(cycle.reason).contains("outside frame")
    }

    @Test
    fun egyLepes_withoutSelfCheck_doesNotDispatch() {
        CoordinateSelfCheck.clear()
        val (ctrl, channel) = controller()
        assertThat(CoordinateSelfCheck.allowsSingleMoveArm()).isFalse()
        assertThat(ctrl.armSingleMove()).isFalse()
        assertThat(ctrl.lastReason).contains("self-check")
        assertThat(ctrl.runCycleIfActive(passVision(), context())).isNull()
        assertThat(channel.calls).isEqualTo(0)
        assertThat(CoordinateSpace.ALIGNMENT_PROVEN).isFalse()
    }

    @Test
    fun recordedUnproven_doesNotUnlockEgyLepes() {
        CoordinateSelfCheck.clear()
        val rec = CoordinateSelfCheck.record(
            expectedX = 350f,
            expectedY = 315f,
            screenWidth = 700,
            screenHeight = 700,
            frameWidth = 700,
            frameHeight = 700,
            rotation = 0,
            statusBarInsetPx = 80,
            observedLuma = null,
        )
        assertThat(rec.status).isEqualTo(CoordinateSelfCheck.STATUS_RECORDED_UNPROVEN)
        assertThat(rec.alignmentProven).isFalse()
        assertThat(rec.expectedY).isEqualTo(315f)
        assertThat(rec.observedNote).contains("NOT MEASURED")
        assertThat(rec.reason).contains("not applied")
        assertThat(rec.reason).contains("NOT proven")
        assertThat(CoordinateSelfCheck.allowsSingleMoveArm()).isFalse()
        assertThat(CoordinateSelfCheck.allowsContinuousStart()).isFalse()
        val (ctrl, channel) = controller()
        assertThat(ctrl.armSingleMove()).isFalse()
        assertThat(ctrl.onStartRequested()).isFalse()
        assertThat(channel.calls).isEqualTo(0)
        val json = rec.toJson()
        assertThat(json).contains("\"alignmentProven\": false")
        assertThat(json).contains("NOT MEASURED")
    }

    @Test
    fun measuredWithinTolerance_unlocksEgyLepes_withoutProvingAlignment() {
        CoordinateSelfCheck.clear()
        val rec = CoordinateSelfCheck.record(
            expectedX = 350f,
            expectedY = 315f,
            screenWidth = 700,
            screenHeight = 700,
            frameWidth = 700,
            frameHeight = 700,
            rotation = 0,
            observedX = 350f,
            observedY = 315f,
        )
        assertThat(rec.status).isEqualTo(CoordinateSelfCheck.STATUS_MEASURED_WITHIN_TOLERANCE)
        assertThat(rec.alignmentProven).isFalse()
        val (ctrl, channel) = controller()
        assertThat(ctrl.armSingleMove()).isTrue()
        assertThat(channel.calls).isEqualTo(0)
        assertThat(rec.toJson()).contains("\"alignmentProven\": false")
    }

    @Test
    fun selfCheck_refusesRotationSizeAndOriginOffset() {
        CoordinateSelfCheck.clear()
        val rotated = CoordinateSelfCheck.record(
            expectedX = 10f,
            expectedY = 10f,
            screenWidth = 700,
            screenHeight = 700,
            frameWidth = 700,
            frameHeight = 700,
            rotation = 1,
        )
        assertThat(rotated.status).isEqualTo(CoordinateSelfCheck.STATUS_REFUSED)
        val mismatch = CoordinateSelfCheck.record(
            expectedX = 10f,
            expectedY = 10f,
            screenWidth = 1080,
            screenHeight = 2400,
            frameWidth = 1080,
            frameHeight = 2340,
            rotation = 0,
        )
        assertThat(mismatch.status).isEqualTo(CoordinateSelfCheck.STATUS_REFUSED)
        assertThat(mismatch.reason).contains("size mismatch")
        val shifted = CoordinateSelfCheck.record(
            expectedX = 10f,
            expectedY = 10f,
            screenWidth = 700,
            screenHeight = 700,
            frameWidth = 700,
            frameHeight = 700,
            rotation = 0,
            originOffsetY = 48,
        )
        assertThat(shifted.status).isEqualTo(CoordinateSelfCheck.STATUS_REFUSED)
        assertThat(shifted.reason).contains("refusing to shift")
        val (ctrl, channel) = controller()
        assertThat(ctrl.armSingleMove()).isFalse()
        assertThat(channel.calls).isEqualTo(0)
    }

    private fun running(): Pair<AutoPlayController, CountingChannel> {
        val pair = controller()
        PlayPermit.allowContinuousStart()
        check(pair.first.onStartRequested())
        return pair
    }

    private fun controller(): Pair<AutoPlayController, CountingChannel> {
        val channel = CountingChannel()
        val screen = screen(700, 700)
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
        return ctrl to channel
    }

    private fun screen(w: Int, h: Int) = ScreenMeasurement(
        widthPx = w,
        heightPx = h,
        densityDpi = 420,
        rotation = 0,
        source = ScreenMeasurement.SOURCE_MAXIMUM_WINDOW,
    )

    private fun context(w: Int = 700, h: Int = 700) = ProductionCycleContext.fromLoopObservation(
        a11yConnected = true,
        captureManagerPresent = true,
        hasFrame = true,
        frameAgeMs = 20L,
        frameSequenceDecision = null,
        frameTimestampMs = 5_000L,
        frameWidth = w,
        frameHeight = h,
        capturedElapsedMs = 10_000L,
        screen = screen(w, h),
        frameSequence = 1L,
    )

    private fun passVision(grid: GridGeometry = GridGeometry.evenSplit(ContentRoi(0, 0, 700, 700), 0.99f)): VisionResult {
        val palette = listOf(
            TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O, TileColor.R,
        )
        val colors = Array(7) { r -> Array(7) { c -> palette[(r * 3 + c * 2) % 6] } }
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
            grid = grid,
            unknownCount = 0,
            confidence = 1f,
            boardConfidence = VisionThresholds.MIN_BOARD_CONFIDENCE,
            gridConfidence = VisionThresholds.MIN_GRID_CONFIDENCE,
            validation = ValidationResult.Pass,
            method = grid.method,
        )
    }
}
