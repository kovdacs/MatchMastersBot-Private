package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import com.match3vision.analyzer.capture.ScreenMeasurement
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
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 0.24.4 production-safety checks.
 *
 * [CountingChannel] replaces only the Android dispatchGesture call.
 * Results are simulation. LIVE PHONE: NOT TESTED.
 */
class ProductionSafety0244Test {

    private class CountingChannel : AccessibilityGestureChannel {
        var dispatchGestureCalls: Int = 0
        override fun canDispatchGestures(): Boolean = true
        override fun diagnose(): String = "counting"
        override fun dispatchGesture(
            gesture: GestureSpec,
            awaitCompletion: Boolean,
            timeoutMs: Long,
        ): InputDispatchResult {
            dispatchGestureCalls += 1
            return InputDispatchResult.Dispatched(gesture, callbackCompleted = true)
        }
    }

    @Test
    fun screen_refusesMeasurementLabeledAsTheCaptureFrame() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ProductionCycleContext.fromLoopObservation(
                a11yConnected = true,
                captureManagerPresent = true,
                hasFrame = true,
                frameAgeMs = 10L,
                frameSequenceDecision = null,
                frameTimestampMs = 1L,
                frameWidth = 1080,
                frameHeight = 2400,
                screen = ScreenMeasurement(
                    widthPx = 1080,
                    heightPx = 2400,
                    source = ScreenMeasurement.SOURCE_FRAME,
                ),
            )
        }
        assertThat(error.message).contains("capture frame")
    }

    @Test
    fun mismatch_blocksThroughProductionContextAndDispatcher() {
        val channel = CountingChannel()
        val screen = screen(1440, 3200)
        val exec = readyExecutor(channel, screen)
        val ctx = context(frameW = 1080, frameH = 2400, screen = screen)
        assertThat(ctx.screenWidth).isEqualTo(1440)
        assertThat(ctx.frameWidth).isEqualTo(1080)
        assertThat(ctx.screenSource).isNotEqualTo(ScreenMeasurement.SOURCE_FRAME)
        assertThat(ctx.coordinateAlignmentProven).isFalse()
        val engine = AutomaticInputEngine(
            enableSwitch = InputEnableSwitch(initiallyEnabled = true),
            executor = exec,
        )
        val result = engine.tryExecute(passVision(), goodMove(), ctx)
        assertThat(channel.dispatchGestureCalls).isEqualTo(0)
        assertThat(result).isInstanceOf(AutomaticInputEngine.ExecuteResult.Stopped::class.java)
        assertThat((result as AutomaticInputEngine.ExecuteResult.Stopped).reason).contains("frame/screen")
        val permit = DispatchPermit.from(ctx, passVision(), goodMoveGesture(), inputEnabled = true)
        val again = exec.dispatchChecked(permit)
        assertThat(channel.dispatchGestureCalls).isEqualTo(0)
        assertThat((again as InputDispatchResult.Failed).reason).contains("TOCTOU")
        assertThat(again.reason).contains("frame/screen")
    }

    @Test
    fun rotation_blocksThroughProductionDispatcher() {
        val channel = CountingChannel()
        val screen = screen(2400, 1080)
        val exec = readyExecutor(channel, screen)
        val ctx = context(frameW = 1080, frameH = 2400, screen = screen)
        val permit = DispatchPermit.from(ctx, passVision(), goodMoveGesture(), inputEnabled = true)
        val result = exec.dispatchChecked(permit)
        assertThat(channel.dispatchGestureCalls).isEqualTo(0)
        assertThat((result as InputDispatchResult.Failed).reason).contains("rotation")
        assertThat(result.reason).contains("axis")
    }

    @Test
    fun missingScreen_blocksDispatch() {
        val channel = CountingChannel()
        val exec = readyExecutor(channel, ScreenMeasurement.unavailable())
        val ctx = context(frameW = 1080, frameH = 2400, screen = ScreenMeasurement.unavailable())
        assertThat(ctx.screenWidth).isEqualTo(0)
        val permit = DispatchPermit.from(ctx, passVision(), goodMoveGesture(), inputEnabled = true)
            .copy(screenWidth = 1080, screenHeight = 2400)
        val result = exec.dispatchChecked(permit)
        assertThat(channel.dispatchGestureCalls).isEqualTo(0)
        assertThat((result as InputDispatchResult.Failed).reason).contains("unavailable")
    }

    @Test
    fun matchingDimensions_continueChecks_butDoNotProveAlignment() {
        val channel = CountingChannel()
        val screen = screen(1080, 2400)
        val exec = readyExecutor(channel, screen)
        val ctx = context(frameW = 1080, frameH = 2400, screen = screen)
        assertThat(ctx.coordinateAlignmentProven).isFalse()
        val space = FrameScreenCoordinatePolicy.assess(1080, 2400, 1080, 2400)
        assertThat(space.mapping).isEqualTo(FrameScreenCoordinatePolicy.Mapping.IDENTITY_FRAME_PIXELS)
        assertThat(space.alignmentProven).isFalse()
        assertThat(space.reason).contains("UNPROVEN")
        val engine = AutomaticInputEngine(
            enableSwitch = InputEnableSwitch(initiallyEnabled = true),
            executor = exec,
        )
        val result = engine.tryExecute(passVision(), goodMove(), ctx)
        assertThat(channel.dispatchGestureCalls).isEqualTo(1)
        assertThat(result).isInstanceOf(AutomaticInputEngine.ExecuteResult.Executed::class.java)
        assertThat(VerificationPolicy.afterDispatch(true)).isEqualTo(VerificationPolicy.PENDING)
        assertThat(VerificationPolicy.afterDispatch(true)).isNotEqualTo(VerificationPolicy.SUCCESS)
    }

    @Test
    fun liveStop_blocksEvenWhenPermitSaysInputEnabled() {
        val channel = CountingChannel()
        val screen = screen(1080, 2400)
        val exec = AccessibilityGestureExecutor(
            serviceProvider = { channel },
            nowElapsedMs = { 10_100L },
            liveProbe = LiveDispatchProbe(
                inputEnabled = { true },
                stopped = { true },
                captureReady = { true },
                screen = { screen },
            ),
        )
        val result = exec.dispatchChecked(okPermit(screen))
        assertThat(channel.dispatchGestureCalls).isEqualTo(0)
        assertThat((result as InputDispatchResult.Failed).reason).contains("STOP")
    }

    @Test
    fun liveInputDisabled_blocksEvenWhenPermitSaysEnabled() {
        val channel = CountingChannel()
        val screen = screen(1080, 2400)
        val exec = AccessibilityGestureExecutor(
            serviceProvider = { channel },
            nowElapsedMs = { 10_100L },
            liveProbe = LiveDispatchProbe(
                inputEnabled = { false },
                stopped = { false },
                captureReady = { true },
                screen = { screen },
            ),
        )
        val result = exec.dispatchChecked(okPermit(screen))
        assertThat(channel.dispatchGestureCalls).isEqualTo(0)
        assertThat((result as InputDispatchResult.Failed).reason).contains("input disabled (live)")
    }

    @Test
    fun liveCaptureOff_blocksEvenWhenPermitSaysOn() {
        val channel = CountingChannel()
        val screen = screen(1080, 2400)
        val exec = AccessibilityGestureExecutor(
            serviceProvider = { channel },
            nowElapsedMs = { 10_100L },
            liveProbe = LiveDispatchProbe(
                inputEnabled = { true },
                stopped = { false },
                captureReady = { false },
                screen = { screen },
            ),
        )
        val result = exec.dispatchChecked(okPermit(screen))
        assertThat(channel.dispatchGestureCalls).isEqualTo(0)
        assertThat((result as InputDispatchResult.Failed).reason).contains("CAPTURE: OFF (live)")
    }

    @Test
    fun liveStateUnavailable_failsClosed() {
        val channel = CountingChannel()
        val exec = AccessibilityGestureExecutor(
            serviceProvider = { channel },
            nowElapsedMs = { 10_100L },
            liveProbe = LiveDispatchProbe.unavailable(),
        )
        val result = exec.dispatchChecked(okPermit(screen(1080, 2400)))
        assertThat(channel.dispatchGestureCalls).isEqualTo(0)
        assertThat((result as InputDispatchResult.Failed).reason).contains("unavailable")
    }

    @Test
    fun unguardedDispatch_doesNotReachChannel_manualPathIsSeparate() {
        val channel = CountingChannel()
        val exec = readyExecutor(channel, screen(1080, 2400))
        val refused = exec.dispatch(goodMoveGesture())
        assertThat(channel.dispatchGestureCalls).isEqualTo(0)
        assertThat((refused as InputDispatchResult.Failed).reason).contains("unguarded dispatch()")
        assertThat(refused.reason).contains("TESZT")
        val manual = exec.dispatchManualTest(goodMoveGesture())
        assertThat(channel.dispatchGestureCalls).isEqualTo(1)
        assertThat(manual).isInstanceOf(InputDispatchResult.Dispatched::class.java)
        assertThat(VerificationPolicy.afterDispatch(true)).isNotEqualTo(VerificationPolicy.SUCCESS)
        assertThat(AccessibilityGestureExecutor.UNGUARDED_REFUSAL).contains("not a vision-gated")
    }

    @Test
    fun scheduledOnly_isNotEligibleForVerification() {
        val decision = GestureCallbackPolicy.decide(
            scheduled = true,
            awaitCallback = false,
            callbackArrived = false,
            completed = false,
            cancelled = false,
        )
        assertThat(decision.kind).isEqualTo(GestureCallbackPolicy.Kind.SCHEDULED_ONLY)
        assertThat(decision.eligibleForVerification()).isFalse()
        assertThat(decision.toResult(goodMoveGesture())).isInstanceOf(InputDispatchResult.Failed::class.java)
        assertThat(decision.reason).contains("SCHEDULED_ONLY")
        assertThat((decision.toResult(goodMoveGesture()) as InputDispatchResult.Failed).reason)
            .contains("not eligible for verification")
        assertThat(decision.verifyLabel()).isEqualTo(VerificationPolicy.FAILED)
        assertThat(decision.verifyLabel()).isNotEqualTo(VerificationPolicy.SUCCESS)
    }

    @Test
    fun callbackCompleted_isPending_notGameStateSuccess() {
        val decision = GestureCallbackPolicy.decide(
            scheduled = true,
            awaitCallback = true,
            callbackArrived = true,
            completed = true,
            cancelled = false,
        )
        assertThat(decision.eligibleForVerification()).isTrue()
        val result = decision.toResult(goodMoveGesture()) as InputDispatchResult.Dispatched
        assertThat(result.callbackCompleted).isTrue()
        assertThat(decision.verifyLabel()).isEqualTo(VerificationPolicy.PENDING)
        assertThat(decision.verifyLabel()).isNotEqualTo(VerificationPolicy.SUCCESS)
    }

    @Test
    fun verify_noNewFrame_andReusedSequence_fail() {
        val reused = VerifyObservation.derive(
            preDispatchSequence = 4L,
            afterSequence = 4L,
            afterElapsedMs = 2_000L,
            dispatchCompletedElapsedMs = 1_500L,
            nowElapsedMs = 2_100L,
            gestureEligible = true,
        )
        assertThat(reused.newFrameAccepted).isFalse()
        val none = VerifyObservation.derive(
            preDispatchSequence = 4L,
            afterSequence = -1L,
            afterElapsedMs = 2_000L,
            dispatchCompletedElapsedMs = 1_500L,
            nowElapsedMs = 2_100L,
            gestureEligible = true,
        )
        assertThat(none.newFrameAccepted).isFalse()
        val fb = feedback(reused, passVision(seed = 3))
        assertThat(fb.verifyStatus).isEqualTo(VerificationPolicy.FAILED)
        assertThat(fb.verifyStatus).isNotEqualTo(VerificationPolicy.SUCCESS)
        assertThat(fb.reason).contains("sequence")
    }

    @Test
    fun verify_equalEarlierMissingZeroAndBackwardsTimestamps_fail() {
        val equal = timed(frameElapsed = 5_000L, dispatchElapsed = 5_000L)
        assertThat(equal.timingFailure()).contains("equal")
        assertThat(feedback(equal, passVision(seed = 2)).reason).contains("equal")

        val earlier = timed(frameElapsed = 4_000L, dispatchElapsed = 5_000L)
        assertThat(earlier.timingFailure()).contains("backwards")
        assertThat(feedback(earlier, passVision(seed = 2)).verifyStatus)
            .isEqualTo(VerificationPolicy.FAILED)

        val missing = VerifyObservation(
            newFrameAccepted = true,
            frameFresh = true,
            gestureEligible = true,
        )
        assertThat(missing.timingFailure()).contains("missing")
        assertThat(feedback(missing, passVision(seed = 2)).reason).contains("missing timestamp")

        val zero = VerifyObservation(
            newFrameAccepted = true,
            frameFresh = true,
            gestureEligible = true,
            frameElapsedMs = 0L,
            dispatchCompletedElapsedMs = 0L,
        )
        assertThat(zero.timingFailure()).contains("zero")
        assertThat(feedback(zero, passVision(seed = 2)).verifyStatus)
            .isEqualTo(VerificationPolicy.FAILED)

        val wallEarlier = VerifyObservation(
            newFrameAccepted = true,
            frameFresh = true,
            gestureEligible = true,
            frameTimestampMs = 1_000L,
            dispatchCompletedAtMs = 2_000L,
        )
        assertThat(wallEarlier.timingFailure()).contains("earlier")
    }

    @Test
    fun verify_staleFrame_andUnchangedBoard_fail() {
        val stale = VerifyObservation.derive(
            preDispatchSequence = 1L,
            afterSequence = 2L,
            afterElapsedMs = 1_000L,
            dispatchCompletedElapsedMs = 900L,
            nowElapsedMs = 1_000L + GestureFailSafe.MAX_FRAME_AGE_MS + 50L,
            gestureEligible = true,
        )
        assertThat(stale.frameFresh).isFalse()
        assertThat(feedback(stale, passVision(seed = 4)).reason.lowercase()).contains("fresh")

        val (ctrl, before, executed) = dispatched()
        val same = ctrl.completeFeedback(
            executed.beforeBoardHash,
            before,
            timed(frameElapsed = 8_000L, dispatchElapsed = 7_000L),
        )!!
        assertThat(same.verifyStatus).isEqualTo(VerificationPolicy.FAILED)
        assertThat(same.reason.lowercase()).contains("unchanged")
    }

    @Test
    fun verify_changeOutsideSwapRegion_isNotSuccess() {
        val (ctrl, before, executed) = dispatched()
        val move = executed.move.move
        val after = changeCellAwayFrom(before, move)
        val fb = ctrl.completeFeedback(
            executed.beforeBoardHash,
            after,
            timed(frameElapsed = 8_000L, dispatchElapsed = 7_000L),
            beforeVision = before,
            attemptedMove = move,
        )!!
        assertThat(fb.verifyStatus).isEqualTo(VerificationPolicy.FAILED)
        assertThat(fb.verifyStatus).isNotEqualTo(VerificationPolicy.SUCCESS)
        assertThat(fb.reason).contains("outside the intended swap region")
        assertThat(SwapRegionCheck.intendedCellsChanged(before, after, move)).isFalse()
    }

    @Test
    fun verify_expectedRegionChange_isUnconfirmed_notSuccess() {
        val (ctrl, before, executed) = dispatched()
        val move = executed.move.move
        val after = changeSwapCells(before, move)
        assertThat(SwapRegionCheck.intendedCellsChanged(before, after, move)).isTrue()
        val fb = ctrl.completeFeedback(
            executed.beforeBoardHash,
            after,
            timed(frameElapsed = 8_000L, dispatchElapsed = 7_000L, region = true),
            beforeVision = before,
            attemptedMove = move,
        )!!
        assertThat(fb.verifyStatus).isEqualTo(VerificationPolicy.BOARD_CHANGED_UNCONFIRMED)
        assertThat(fb.verifyStatus).isNotEqualTo(VerificationPolicy.SUCCESS)
        assertThat(fb.reason).contains("MOVE UNCONFIRMED")
        assertThat(SimulationMarker.BANNER).contains("NOT PROVEN")
    }

    private fun screen(w: Int, h: Int) = ScreenMeasurement(
        widthPx = w,
        heightPx = h,
        densityDpi = 420,
        rotation = 0,
        source = ScreenMeasurement.SOURCE_MAXIMUM_WINDOW,
    )

    private fun readyExecutor(channel: CountingChannel, screen: ScreenMeasurement) =
        ProductionInstall.accessibilityExecutor(
            serviceProvider = { channel },
            nowElapsedMs = { 10_100L },
            liveProbe = LiveDispatchProbe(
                inputEnabled = { true },
                stopped = { false },
                captureReady = { true },
                screen = { screen },
            ),
        )

    private fun context(frameW: Int, frameH: Int, screen: ScreenMeasurement) =
        ProductionCycleContext.fromLoopObservation(
            a11yConnected = true,
            captureManagerPresent = true,
            hasFrame = true,
            frameAgeMs = 20L,
            frameSequenceDecision = null,
            frameTimestampMs = 5_000L,
            frameWidth = frameW,
            frameHeight = frameH,
            capturedElapsedMs = 10_000L,
            screen = screen,
            frameSequence = 3L,
        )

    private fun okPermit(screen: ScreenMeasurement) = DispatchPermit(
        a11yConnected = true,
        captureOn = true,
        hasFrame = true,
        frameAgeMs = 20L,
        visionPass = true,
        inputEnabled = true,
        screenWidth = screen.widthPx,
        screenHeight = screen.heightPx,
        frameWidth = 1080,
        frameHeight = 2400,
        gesture = goodMoveGesture(),
        simulated = false,
        sequenceAllowed = true,
        capturedElapsedMs = 10_000L,
        screenSource = screen.source,
    )

    private fun goodMoveGesture() = GestureSpec(120f, 400f, 200f, 400f, 120L)

    private fun timed(
        frameElapsed: Long,
        dispatchElapsed: Long,
        region: Boolean? = null,
    ) = VerifyObservation(
        newFrameAccepted = true,
        frameFresh = true,
        gestureEligible = true,
        frameElapsedMs = frameElapsed,
        dispatchCompletedElapsedMs = dispatchElapsed,
        preDispatchSequence = 1L,
        afterSequence = 2L,
        intendedRegionChanged = region,
    )

    private fun feedback(verify: VerifyObservation, after: VisionResult): InputLoopController.CycleResult {
        val (ctrl, _, executed) = dispatched()
        return ctrl.completeFeedback(executed.beforeBoardHash, after, verify)!!
    }

    private fun dispatched(): Triple<AutoPlayController, VisionResult, AutomaticInputEngine.ExecuteResult.Executed> {
        val exec = RecordingInputGestureExecutor(ready = true)
        val sw = InputEnableSwitch.disabledByDefault()
        val ctrl = AutoPlayController(
            enableSwitch = sw,
            inputLoop = InputLoopController(
                inputEngine = AutomaticInputEngine(enableSwitch = sw, executor = exec),
            ),
        )
        PlayPermit.allowContinuousStart()
        check(ctrl.onStartRequested(a11yConnected = true, captureReady = true, overlayReady = true))
        val before = passVision()
        val cycle = ctrl.runCycleIfActive(before)!!
        val executed = cycle.executed as AutomaticInputEngine.ExecuteResult.Executed
        return Triple(ctrl, before, executed)
    }

    private fun cell(color: TileColor) = CellVision(
        color = color,
        shape = TileShape.CIRCLE,
        special = SpecialType.NONE,
        occluded = false,
        confidence = 1f,
        isUnknown = false,
    )

    private fun passVision(
        roi: ContentRoi = ContentRoi(0, 0, 700, 700),
        seed: Int = 0,
    ): VisionResult {
        val palette = listOf(
            TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O, TileColor.R,
        )
        val colors = Array(7) { r ->
            Array(7) { c -> palette[(r * 3 + c * 2 + seed) % 6] }
        }
        colors[0][0] = TileColor.R
        colors[0][1] = TileColor.R
        colors[0][2] = TileColor.B
        colors[0][3] = TileColor.Y
        colors[1][2] = TileColor.R
        val cells = Array(7) { r -> Array(7) { c -> cell(colors[r][c]) } }
        return VisionResult(
            board = VisionBoard(cells),
            grid = GridGeometry.evenSplit(roi, VisionThresholds.MIN_GRID_CONFIDENCE),
            unknownCount = 0,
            confidence = VisionThresholds.MIN_BOARD_CONFIDENCE,
            boardConfidence = VisionThresholds.MIN_BOARD_CONFIDENCE,
            gridConfidence = VisionThresholds.MIN_GRID_CONFIDENCE,
            validation = ValidationResult.Pass,
            method = GridMethod.EVEN_SPLIT,
        )
    }

    private fun goodMove(move: Move = Move(0, 2, 1, 2)) = MoveEvaluation(
        move = move,
        totalScore = 42f,
        matchScore = 30f,
        cascadeScore = 5f,
        specialScore = 0f,
        starScore = 0f,
        boosterScore = 0f,
        futureScore = 2f,
        riskPenalty = 0f,
        expectedValue = 42f,
        confidence = 0.95f,
        uncertain = false,
        reasons = listOf("test move"),
    )

    private fun changeCellAwayFrom(before: VisionResult, move: Move): VisionResult {
        val cells = Array(7) { r -> Array(7) { c -> before.board.get(r, c) } }
        var r = 0
        var c = 0
        while ((r == move.r1 && c == move.c1) || (r == move.r2 && c == move.c2)) {
            c += 1
            if (c == 7) {
                c = 0
                r += 1
            }
        }
        val current = cells[r][c].color
        val next = if (current == TileColor.B) TileColor.Y else TileColor.B
        cells[r][c] = cell(next)
        return before.copy(board = VisionBoard(cells))
    }

    private fun changeSwapCells(before: VisionResult, move: Move): VisionResult {
        val cells = Array(7) { r -> Array(7) { c -> before.board.get(r, c) } }
        val a = cells[move.r1][move.c1]
        val b = cells[move.r2][move.c2]
        cells[move.r1][move.c1] = b
        cells[move.r2][move.c2] = a
        if (a.color == b.color && a.shape == b.shape) {
            cells[move.r1][move.c1] = cell(TileColor.G)
        }
        return before.copy(board = VisionBoard(cells))
    }
}
