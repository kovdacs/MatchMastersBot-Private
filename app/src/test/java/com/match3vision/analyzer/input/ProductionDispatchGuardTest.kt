package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import com.match3vision.analyzer.capture.FrameSequenceGate
import com.match3vision.analyzer.capture.ScreenMeasurement
import com.match3vision.analyzer.evaluation.MoveEvaluation
import com.match3vision.analyzer.moves.Move
import com.match3vision.analyzer.overlay.LivePipelineStatus
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
 * Guards through the production [AccessibilityGestureExecutor].
 *
 * The Android framework call is [AccessibilityGestureChannel.dispatchGesture].
 * [CountingChannel] replaces only that channel (no MediaProjection, no real
 * AccessibilityService instance). [AccessibilityGestureExecutor] and
 * [DispatchRecheck] are the production classes.
 *
 * If the TOCTOU `if (!again.allow) return` in [AccessibilityGestureExecutor.dispatchChecked]
 * is removed, every `guard_*` test below fails because [CountingChannel.dispatchGesture]
 * increments.
 */
class ProductionDispatchGuardTest {

    private class CountingChannel(
        private val ready: Boolean = true,
    ) : AccessibilityGestureChannel {
        var dispatchGestureCalls: Int = 0
        override fun canDispatchGestures(): Boolean = ready
        override fun diagnose(): String = "counting-channel ready=$ready"
        override fun dispatchGesture(
            gesture: GestureSpec,
            awaitCompletion: Boolean,
            timeoutMs: Long,
        ): InputDispatchResult {
            dispatchGestureCalls += 1
            return InputDispatchResult.Dispatched(gesture)
        }
    }

    private fun okGesture() = GestureSpec(120f, 400f, 200f, 400f, 120L)

    private fun okPermit(gesture: GestureSpec = okGesture()) = DispatchPermit(
        a11yConnected = true,
        captureOn = true,
        hasFrame = true,
        frameAgeMs = 20L,
        visionPass = true,
        inputEnabled = true,
        screenWidth = 1080,
        screenHeight = 2400,
        frameWidth = 1080,
        frameHeight = 2400,
        gesture = gesture,
        simulated = false,
        sequenceAllowed = true,
    )

    /** Real executor. Channel is the only substitute. Live probe echoes the permit. */
    private fun blocked(permit: DispatchPermit, ready: Boolean = true) {
        val channel = CountingChannel(ready = ready)
        val captured = if (permit.capturedElapsedMs > 0L) permit.capturedElapsedMs else 10_000L
        val age = permit.frameAgeMs.coerceAtLeast(0L)
        val screen = if (permit.screenWidth > 0 && permit.screenHeight > 0) {
            ScreenMeasurement(
                widthPx = permit.screenWidth,
                heightPx = permit.screenHeight,
                source = ScreenMeasurement.SOURCE_MAXIMUM_WINDOW,
            )
        } else {
            ScreenMeasurement.unavailable()
        }
        val executor = AccessibilityGestureExecutor(
            serviceProvider = { channel },
            nowElapsedMs = { captured + age },
            liveProbe = LiveDispatchProbe(
                inputEnabled = { permit.inputEnabled },
                stopped = { false },
                captureReady = { permit.captureOn },
                screen = { screen },
            ),
        )
        val stamped = permit.copy(capturedElapsedMs = captured)
        val result = executor.dispatchChecked(stamped)
        assertThat(result).isInstanceOf(InputDispatchResult.Failed::class.java)
        assertThat(channel.dispatchGestureCalls).isEqualTo(0)
        assertThat((result as InputDispatchResult.Failed).reason).contains("TOCTOU")
        assertThat((result as InputDispatchResult.Failed).reason).contains("dispatchGesture")
    }

    @Test
    fun guard_staleFrame_realDispatcher_doesNotCallDispatchGesture() {
        blocked(okPermit().copy(frameAgeMs = GestureFailSafe.MAX_FRAME_AGE_MS + 1))
    }

    @Test
    fun guard_missingFrame_realDispatcher_doesNotCallDispatchGesture() {
        blocked(okPermit().copy(hasFrame = false))
    }

    @Test
    fun guard_sequenceRejected_realDispatcher_doesNotCallDispatchGesture() {
        blocked(okPermit().copy(sequenceAllowed = false))
    }

    @Test
    fun guard_visionHold_realDispatcher_doesNotCallDispatchGesture() {
        blocked(okPermit().copy(visionPass = false))
    }

    @Test
    fun guard_inputDisabled_realDispatcher_doesNotCallDispatchGesture() {
        blocked(okPermit().copy(inputEnabled = false))
    }

    @Test
    fun guard_a11yDisconnected_realDispatcher_doesNotCallDispatchGesture() {
        blocked(okPermit().copy(a11yConnected = false))
    }

    @Test
    fun guard_a11yDroppedAfterPlan_realDispatcher_rereadsChannel() {
        // Permit still says connected. Live channel is not ready. Must not dispatch.
        blocked(okPermit().copy(a11yConnected = true), ready = false)
    }

    @Test
    fun guard_captureOff_realDispatcher_doesNotCallDispatchGesture() {
        blocked(okPermit().copy(captureOn = false))
    }

    @Test
    fun guard_offScreen_realDispatcher_doesNotCallDispatchGesture() {
        blocked(okPermit().copy(gesture = GestureSpec(5000f, 9000f, 5100f, 9000f, 120L)))
    }

    @Test
    fun guard_frameScreenMismatch_realDispatcher_doesNotCallDispatchGesture() {
        blocked(okPermit().copy(frameWidth = 1080, frameHeight = 2400, screenWidth = 1440, screenHeight = 3200))
    }

    @Test
    fun guard_rotationSwapsAxes_realDispatcher_doesNotCallDispatchGesture() {
        blocked(okPermit().copy(frameWidth = 1080, frameHeight = 2400, screenWidth = 2400, screenHeight = 1080))
    }

    @Test
    fun guard_missingMonotonicClock_failsClosed() {
        val channel = CountingChannel()
        val screen = ScreenMeasurement(
            widthPx = 1080,
            heightPx = 2400,
            source = ScreenMeasurement.SOURCE_MAXIMUM_WINDOW,
        )
        val executor = AccessibilityGestureExecutor(
            serviceProvider = { channel },
            nowElapsedMs = { 0L },
            liveProbe = LiveDispatchProbe(
                inputEnabled = { true },
                stopped = { false },
                captureReady = { true },
                screen = { screen },
            ),
        )
        val permit = okPermit().copy(frameAgeMs = 10L, capturedElapsedMs = 0L)
        val result = executor.dispatchChecked(permit)
        assertThat(channel.dispatchGestureCalls).isEqualTo(0)
        assertThat((result as InputDispatchResult.Failed).reason)
            .contains("monotonic frame age unavailable")
    }

    @Test
    fun guard_frameAgedDuringAnalysis_realDispatcher_remeasuresBeforeDispatchGesture() {
        val channel = CountingChannel()
        val screen = ScreenMeasurement(
            widthPx = 1080,
            heightPx = 2400,
            source = ScreenMeasurement.SOURCE_MAXIMUM_WINDOW,
        )
        val executor = AccessibilityGestureExecutor(
            serviceProvider = { channel },
            nowElapsedMs = { 1_000L + 4_000L },
            liveProbe = LiveDispatchProbe(
                inputEnabled = { true },
                stopped = { false },
                captureReady = { true },
                screen = { screen },
            ),
        )
        // Plan-time age was 10 ms. The monotonic clock at dispatch is 4 s later.
        val permit = okPermit().copy(frameAgeMs = 10L, capturedElapsedMs = 1_000L)
        val result = executor.dispatchChecked(permit)
        assertThat(channel.dispatchGestureCalls).isEqualTo(0)
        assertThat(result).isInstanceOf(InputDispatchResult.Failed::class.java)
        assertThat((result as InputDispatchResult.Failed).reason).contains("stale")
        assertThat((result as InputDispatchResult.Failed).reason).contains("dispatchGesture")
    }

    @Test
    fun guard_simulatedContext_realDispatcher_doesNotCallDispatchGesture() {
        blocked(okPermit().copy(simulated = true))
    }

    @Test
    fun guard_unknownScreenBounds_realDispatcher_doesNotCallDispatchGesture() {
        blocked(okPermit().copy(screenWidth = 0, screenHeight = 0))
    }

    @Test
    fun happyPath_realDispatcher_callsDispatchGestureOnce_andIsNotVerifySuccess() {
        val channel = CountingChannel()
        val screen = ScreenMeasurement(
            widthPx = 1080,
            heightPx = 2400,
            source = ScreenMeasurement.SOURCE_MAXIMUM_WINDOW,
        )
        val executor = ProductionInstall.accessibilityExecutor(
            serviceProvider = { channel },
            nowElapsedMs = { 10_400L },
            liveProbe = LiveDispatchProbe(
                inputEnabled = { true },
                stopped = { false },
                captureReady = { true },
                screen = { screen },
            ),
        )
        val sw = InputEnableSwitch(initiallyEnabled = true)
        val engine = AutomaticInputEngine(enableSwitch = sw, executor = executor)
        val vision = passVision(ContentRoi(37, 511, 1043, 2018))
        val ctx = ProductionCycleContext.fromLoopObservation(
            a11yConnected = true,
            captureManagerPresent = true,
            hasFrame = true,
            frameAgeMs = 15L,
            frameSequenceDecision = null,
            frameTimestampMs = 5_000L,
            frameWidth = 1080,
            frameHeight = 2400,
            capturedElapsedMs = 10_000L,
            screen = screen,
        )
        assertThat(ctx.screenSource).isEqualTo(ScreenMeasurement.SOURCE_MAXIMUM_WINDOW)
        assertThat(ctx.coordinateAlignmentProven).isFalse()
        val result = engine.tryExecute(vision, goodMove(Move(4, 2, 4, 3)), ctx)
        assertThat(channel.dispatchGestureCalls).isEqualTo(1)
        assertThat(result).isInstanceOf(AutomaticInputEngine.ExecuteResult.Executed::class.java)
        assertThat(VerificationPolicy.afterDispatch(dispatchSucceeded = true))
            .isEqualTo(VerificationPolicy.PENDING)
        assertThat(VerificationPolicy.afterDispatch(dispatchSucceeded = true))
            .isNotEqualTo(VerificationPolicy.SUCCESS)
    }

    @Test
    fun callback_onCancelled_isFailed_notVerifySuccess() {
        val decision = GestureCallbackPolicy.decide(
            scheduled = true,
            awaitCallback = true,
            callbackArrived = true,
            completed = false,
            cancelled = true,
        )
        assertThat(decision.kind).isEqualTo(GestureCallbackPolicy.Kind.CANCELLED)
        assertThat(decision.toResult(okGesture())).isInstanceOf(InputDispatchResult.Failed::class.java)
        assertThat(decision.reason).contains("onCancelled")
        assertThat(decision.verifyLabel()).isEqualTo(VerificationPolicy.FAILED)
        assertThat(decision.verifyLabel()).isNotEqualTo(VerificationPolicy.SUCCESS)
    }

    @Test
    fun callback_neverArrives_isTimeout_notVerifySuccess() {
        val decision = GestureCallbackPolicy.decide(
            scheduled = true,
            awaitCallback = true,
            callbackArrived = false,
            completed = false,
            cancelled = false,
        )
        assertThat(decision.kind).isEqualTo(GestureCallbackPolicy.Kind.TIMED_OUT)
        assertThat(decision.reason).contains("never arrived")
        assertThat(decision.toResult(okGesture())).isInstanceOf(InputDispatchResult.Failed::class.java)
        assertThat(decision.verifyLabel()).isEqualTo(VerificationPolicy.FAILED)
    }

    @Test
    fun callback_onCompleted_staysVerifyPending() {
        val decision = GestureCallbackPolicy.decide(
            scheduled = true,
            awaitCallback = true,
            callbackArrived = true,
            completed = true,
            cancelled = false,
        )
        assertThat(decision.kind).isEqualTo(GestureCallbackPolicy.Kind.COMPLETED)
        assertThat(decision.toResult(okGesture())).isInstanceOf(InputDispatchResult.Dispatched::class.java)
        assertThat(decision.verifyLabel()).isEqualTo(VerificationPolicy.PENDING)
        assertThat(decision.verifyLabel()).isNotEqualTo(VerificationPolicy.SUCCESS)
    }

    @Test
    fun frameClock_usesMonotonicAge_threshold3000_backwardsIsStale() {
        assertThat(FrameClock.NAME).contains("elapsedRealtime")
        assertThat(FrameClock.STALE_AFTER_MS).isEqualTo(3_000L)
        assertThat(GestureFailSafe.MAX_FRAME_AGE_MS).isEqualTo(3_000L)
        // 400 ms of monotonic time. A wall-clock jump is not an input to ageMs.
        assertThat(FrameClock.ageMs(10_000L, 10_400L)).isEqualTo(400L)
        assertThat(FrameClock.isStale(400L)).isFalse()
        assertThat(FrameClock.isStale(3_000L)).isFalse()
        assertThat(FrameClock.isStale(3_001L)).isTrue()
        assertThat(FrameClock.ageMs(50_000L, 1_000L)).isGreaterThan(3_000L)
        assertThat(FrameClock.ageMs(0L, 10_000L)).isGreaterThan(3_000L)
    }

    @Test
    fun coordinate_independentCenters_nonzeroRoi_noDensity_rotationRefused() {
        val left = 37
        val top = 511
        val right = 1043
        val bottom = 2018
        val row = 4
        val col = 2
        val cellW = (right - left) / 7.0
        val cellH = (bottom - top) / 7.0
        val startX = left + (col + 0.5) * cellW
        val startY = top + (row + 0.5) * cellH
        val endX = left + ((col + 1) + 0.5) * cellW
        val endY = startY
        val grid = GridGeometry.evenSplit(ContentRoi(left, top, right, bottom), 0.99f)
        val gesture = TouchCoordinateMapper(swipeDurationMs = 120L)
            .toGesture(Move(row, col, row, col + 1), grid)
        assertThat(gesture.startX.toDouble()).isWithin(0.05).of(startX)
        assertThat(gesture.startY.toDouble()).isWithin(0.05).of(startY)
        assertThat(gesture.endX.toDouble()).isWithin(0.05).of(endX)
        assertThat(gesture.endY.toDouble()).isWithin(0.05).of(endY)
        // Letterbox / ROI offset is included. Not relative to the ROI origin.
        assertThat(gesture.startY).isGreaterThan(top.toFloat())
        assertThat(gesture.startX).isGreaterThan(left.toFloat())
        // Not an integer: the mapper must not round to px ints.
        assertThat(gesture.startX).isNotEqualTo(gesture.startX.toInt().toFloat())
        val density = 2.75
        assertThat(gesture.startX.toDouble()).isNotWithin(1.0).of(startX * density)
        assertThat(gesture.durationMs).isEqualTo(120L)
        val rotated = FrameScreenCoordinatePolicy.assess(1080, 2400, 2400, 1080)
        assertThat(rotated.mapping).isEqualTo(FrameScreenCoordinatePolicy.Mapping.REFUSED)
        assertThat(rotated.deviceDependent).isTrue()
    }

    @Test
    fun verify_frameNotLaterThanDispatch_cannotSucceed() {
        val (ctrl, exec) = startedProbe()
        val cycle = ctrl.runCycleIfActive(passVision(), measured())
        val executed = cycle!!.executed as AutomaticInputEngine.ExecuteResult.Executed
        val sameInstant = ctrl.completeFeedback(
            executed.beforeBoardHash,
            passVision(seed = 3),
            VerifyObservation(
                newFrameAccepted = true,
                frameFresh = true,
                frameTimestampMs = 8_000L,
                dispatchCompletedAtMs = 8_000L,
                gestureEligible = true,
            ),
        )
        assertThat(sameInstant!!.verifyStatus).isEqualTo(VerificationPolicy.FAILED)
        assertThat(sameInstant.reason).contains("equal to dispatch completion")
        assertThat(sameInstant.reason).contains("no SUCCESS")
        assertThat(sameInstant.verifyStatus).isNotEqualTo(VerificationPolicy.SUCCESS)
        assertThat(exec.dispatched).hasSize(1)
        assertThat(ProductionPath.isSimulationExecutor(exec)).isTrue()
    }

    @Test
    fun verify_noNewFrame_cannotSucceed() {
        val (ctrl, _) = startedProbe()
        val cycle = ctrl.runCycleIfActive(passVision(), measured())
        val executed = cycle!!.executed as AutomaticInputEngine.ExecuteResult.Executed
        val missing = ctrl.completeFeedback(
            executed.beforeBoardHash,
            passVision(seed = 3),
            VerifyObservation(newFrameAccepted = false, frameFresh = false, reason = "no new frame"),
        )
        assertThat(missing!!.verifyStatus).isEqualTo(VerificationPolicy.FAILED)
        assertThat(missing.verifyStatus).isNotEqualTo(VerificationPolicy.SUCCESS)
    }

    @Test
    fun verify_identicalBoard_cannotSucceed() {
        val (ctrl, _) = startedProbe()
        val before = passVision(seed = 1)
        val cycle = ctrl.runCycleIfActive(before, measured())
        val executed = cycle!!.executed as AutomaticInputEngine.ExecuteResult.Executed
        val same = ctrl.completeFeedback(
            executed.beforeBoardHash,
            before,
            VerifyObservation(
                newFrameAccepted = true,
                frameFresh = true,
                gestureEligible = true,
                frameElapsedMs = 6_000L,
                dispatchCompletedElapsedMs = 5_000L,
            ),
        )
        assertThat(same!!.verifyStatus).isEqualTo(VerificationPolicy.FAILED)
        assertThat(same.reason.lowercase()).contains("unchanged")
        val flagged = ctrl.completeFeedback(
            executed.beforeBoardHash,
            passVision(seed = 4),
            VerifyObservation(
                newFrameAccepted = true,
                frameFresh = true,
                boardUnchanged = true,
            ),
        )
        assertThat(flagged!!.verifyStatus).isEqualTo(VerificationPolicy.FAILED)
    }

    @Test
    fun bubble_showsHoldReasonAndConfidencesWhenVisionDeclines() {
        val reason = "HOLD: grid confidence 0.972 < 0.980 (Decision AI blocked)"
        val text = LivePipelineStatus(
            mode = "RUNNING",
            phase = "TARTÁS",
            frame = "received",
            boardRoi = "LTRB(37,511,1043,2018)",
            gridConf = 0.972f,
            boardConf = 0.961f,
            unknownCount = 1,
            visionGate = "HOLD — $reason",
            moveCount = 0,
            selectedMove = "none",
            a11y = "CONNECTED",
            inputReady = "BLOCKED",
            lastDispatch = "NONE",
            verifyStatus = "PENDING",
            frameSequence = "seq=4",
            frameAgeMs = 180L,
            captureStatus = "ON",
            firstBlock = reason,
            gestureStatus = "NOT CREATED",
            frameTimestampMs = 1_700_000_000_000L,
            frameWidth = 1080,
            frameHeight = 2400,
            frameFreshness = "FRESH",
            moveCandidates = 0,
        ).bubbleLines(compact = true)
        assertThat(text).contains("VISION: HOLD")
        assertThat(text).contains("0.972")
        assertThat(text).contains("0.980")
        assertThat(text).contains("grid=0.972")
        assertThat(text).contains("board=0.961")
        assertThat(text).contains("unk=1")
        assertThat(text).contains("age=180ms")
        assertThat(text).contains("A11Y: CONNECTED")
        assertThat(text).contains("DISPATCH: NOT STARTED")
        assertThat(text).doesNotContain("VERIFY: SUCCESS")
        assertThat(VisionThresholds.MIN_GRID_CONFIDENCE).isEqualTo(0.98f)
    }

    @Test
    fun engine_staleContext_doesNotReachRealDispatchGesture() {
        val channel = CountingChannel()
        val screen = ScreenMeasurement(
            widthPx = 1080,
            heightPx = 2400,
            source = ScreenMeasurement.SOURCE_MAXIMUM_WINDOW,
        )
        val executor = ProductionInstall.accessibilityExecutor(
            serviceProvider = { channel },
            nowElapsedMs = { 20_000L },
            liveProbe = LiveDispatchProbe(
                inputEnabled = { true },
                stopped = { false },
                captureReady = { true },
                screen = { screen },
            ),
        )
        val sw = InputEnableSwitch(initiallyEnabled = true)
        val engine = AutomaticInputEngine(enableSwitch = sw, executor = executor)
        val ctx = ProductionCycleContext.fromLoopObservation(
            a11yConnected = true,
            captureManagerPresent = true,
            hasFrame = true,
            frameAgeMs = 9_000L,
            frameSequenceDecision = FrameSequenceGate.Decision(
                FrameSequenceGate.Verdict.ALLOW_INITIAL,
                "frame accepted",
                allow = true,
            ),
            frameTimestampMs = 1L,
            frameWidth = 1080,
            frameHeight = 2400,
            capturedElapsedMs = 1_000L,
            screen = screen,
        )
        val result = engine.tryExecute(passVision(), goodMove(), ctx)
        assertThat(channel.dispatchGestureCalls).isEqualTo(0)
        assertThat(result).isInstanceOf(AutomaticInputEngine.ExecuteResult.Held::class.java)
    }

    private fun cell(color: TileColor) = CellVision(
        color = color,
        shape = TileShape.CIRCLE,
        special = SpecialType.NONE,
        occluded = false,
        confidence = 1f,
        isUnknown = color == TileColor.UNKNOWN,
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
        colors[6][6] = palette[seed % 6]
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

    private fun goodMove(move: Move = Move(0, 0, 0, 1)) = MoveEvaluation(
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

    private fun measured() = ProductionCycleContext.fromLoopObservation(
        a11yConnected = true,
        captureManagerPresent = true,
        hasFrame = true,
        frameAgeMs = 20L,
        frameSequenceDecision = null,
        frameTimestampMs = 1_700_000_000_000L,
        frameWidth = 1080,
        frameHeight = 2400,
        screen = ScreenMeasurement(
            widthPx = 1080,
            heightPx = 2400,
            source = ScreenMeasurement.SOURCE_MAXIMUM_WINDOW,
        ),
    )

    /** Probe executor so verify tests can obtain a before-hash. Not the production channel. */
    private fun startedProbe(): Pair<AutoPlayController, RecordingInputGestureExecutor> {
        val exec = RecordingInputGestureExecutor(ready = true)
        val sw = InputEnableSwitch.disabledByDefault()
        val ctrl = AutoPlayController(
            enableSwitch = sw,
            inputLoop = InputLoopController(
                inputEngine = AutomaticInputEngine(enableSwitch = sw, executor = exec),
            ),
        )
        assertThat(
            ctrl.onStartRequested(a11yConnected = true, captureReady = true, overlayReady = true),
        ).isTrue()
        return ctrl to exec
    }
}
