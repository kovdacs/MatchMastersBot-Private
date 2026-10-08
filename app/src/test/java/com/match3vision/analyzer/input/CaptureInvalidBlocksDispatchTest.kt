package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.CaptureBufferGate
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
import com.match3vision.analyzer.vision.VisionPipeline
import com.match3vision.analyzer.vision.VisionResult
import com.match3vision.analyzer.vision.VisionThresholds
import org.junit.Test

/**
 * A failed capture must not be analyzed as a zero buffer.
 * CountingChannel is the production dispatchGesture stand-in.
 */
class CaptureInvalidBlocksDispatchTest {

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
    fun failedCopy_zeroSubstitute_continuous_doesNotReachVisionMoveOrDispatch() {
        refused(singleMove = false)
    }

    @Test
    fun failedCopy_zeroSubstitute_egyLepes_doesNotReachVisionMoveOrDispatch() {
        refused(singleMove = true)
    }

    @Test
    fun missingAndRecycledBuffers_areCaptureInvalid() {
        assertThat(
            CaptureBufferGate.refusalReason(
                copySucceeded = false,
                width = 0,
                height = 0,
                bufferLength = 0,
            ),
        ).contains(CaptureBufferGate.FAILURE_CLASS)
        val zeros = IntArray(1080 * 100)
        assertThat(
            CaptureBufferGate.refusalReason(
                copySucceeded = false,
                width = 1080,
                height = 100,
                bufferLength = zeros.size,
            ),
        ).contains("zero substitute was not analyzed")
        var calls = 0
        val admission = CaptureBufferGate.analyzeIfAdmitted(
            copySucceeded = false,
            width = 1080,
            height = 100,
            bufferLength = zeros.size,
        ) {
            calls += 1
            VisionPipeline()
        }
        assertThat(admission.admitted).isFalse()
        assertThat(admission.value).isNull()
        assertThat(calls).isEqualTo(0)
    }

    @Test
    fun copiedBlackFrame_isAdmitted_andIsNotCaptureInvalid() {
        val (ctrl, channel) = armed(singleMove = false)
        var visionCalls = 0
        val zeros = IntArray(700 * 700)
        val routed = ProductionFrameRouter.route(
            copySucceeded = true,
            width = 700,
            height = 700,
            bufferLength = zeros.size,
            controller = ctrl,
            context = context(),
            analyze = {
                visionCalls += 1
                passVision().copy(
                    validation = ValidationResult.Hold("HOLD — copied black frame"),
                )
            },
        )
        assertThat(routed.admitted).isTrue()
        assertThat(routed.failureClass).isNull()
        assertThat(visionCalls).isEqualTo(1)
        assertThat(channel.calls).isEqualTo(0)
        assertThat(routed.cycle!!.visionGate).contains("HOLD")
    }

    private fun refused(singleMove: Boolean) {
        val (ctrl, channel) = armed(singleMove)
        if (singleMove) {
            assertThat(ctrl.singleMove.allowsProductionDispatch()).isTrue()
        } else {
            assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.RUNNING)
        }
        assertThat(ctrl.enableSwitch().isEnabled()).isTrue()
        var visionPipelineCalls = 0
        var moveAnalysisCalls = 0
        val zeros = IntArray(700 * 700)
        val routed = ProductionFrameRouter.route(
            copySucceeded = false,
            width = 700,
            height = 700,
            bufferLength = zeros.size,
            controller = ctrl,
            context = context(),
            analyze = {
                visionPipelineCalls += 1
                VisionPipeline()
                passVision()
            },
            moveAnalysis = { moveAnalysisCalls += 1 },
        )
        assertThat(routed.admitted).isFalse()
        assertThat(routed.failureClass).isEqualTo(CaptureBufferGate.FAILURE_CLASS)
        assertThat(routed.vision).isNull()
        assertThat(routed.cycle).isNull()
        assertThat(visionPipelineCalls).isEqualTo(0)
        assertThat(moveAnalysisCalls).isEqualTo(0)
        assertThat(channel.calls).isEqualTo(0)
        val bundle = DiagnosticBundle.fromObservation(
            appVersion = "0.24.5-diagnostics-single-move",
            versionCode = 18,
            sourceCommit = "test",
            diagnosticTimestampMs = 1L,
            vision = null,
            screen = screen(),
            frameWidth = 700,
            frameHeight = 700,
            frameSequence = 1L,
            captureTimestampMs = 1L,
            frameAgeMs = 10L,
            frameElapsedMs = 10L,
            cadence = null,
            accessibilityConnected = true,
            gestureCapability = "test",
            captureOn = true,
            hasFrame = true,
            moveAnalysis = "should-not-appear",
            selectedMove = "0,0→0,1",
            coordinateReason = "",
            coordinateRefused = false,
            dispatchStatus = "SUCCESS",
            callbackOutcome = "onCompleted",
            verificationStatus = VerificationPolicy.SUCCESS,
            verificationReason = "",
            simulated = true,
            captureInvalidReason = routed.reason,
        )
        assertThat(bundle.failureClass).isEqualTo(DiagnosticBundle.CLASS_CAPTURE_INVALID)
        assertThat(bundle.visionGate).isEqualTo("HOLD")
        assertThat(bundle.visionGate).doesNotContain("PASS")
        assertThat(bundle.visionDecisions).contains("VisionPipeline was not called")
        assertThat(bundle.moveAnalysis).contains("CAPTURE_INVALID")
        assertThat(bundle.selectedMove).isEqualTo("none")
        assertThat(bundle.dispatchStatus).isEqualTo("NOT STARTED")
        assertThat(bundle.verificationStatus).contains("REFUSED")
        assertThat(bundle.diagnosticInfluencesGate).isFalse()
        assertThat(bundle.blackFrame).contains("not a black frame")
    }

    private fun armed(singleMove: Boolean): Pair<AutoPlayController, CountingChannel> {
        val channel = CountingChannel()
        val screen = screen()
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
        if (singleMove) {
            val rec = CoordinateSelfCheck.record(
                expectedX = 350f,
                expectedY = 315f,
                screenWidth = 700,
                screenHeight = 700,
                frameWidth = 700,
                frameHeight = 700,
                rotation = 0,
            )
            check(rec.status == CoordinateSelfCheck.STATUS_RECORDED_UNPROVEN)
            check(ctrl.armSingleMove())
        } else {
            PlayPermit.allowContinuousStart()
            check(ctrl.onStartRequested())
        }
        return ctrl to channel
    }

    private fun screen() = ScreenMeasurement(
        widthPx = 700,
        heightPx = 700,
        densityDpi = 420,
        rotation = 0,
        source = ScreenMeasurement.SOURCE_MAXIMUM_WINDOW,
    )

    private fun context() = ProductionCycleContext.fromLoopObservation(
        a11yConnected = true,
        captureManagerPresent = true,
        hasFrame = true,
        frameAgeMs = 20L,
        frameSequenceDecision = null,
        frameTimestampMs = 5_000L,
        frameWidth = 700,
        frameHeight = 700,
        capturedElapsedMs = 10_000L,
        screen = screen(),
        frameSequence = 1L,
    )

    private fun passVision(): VisionResult {
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
            grid = GridGeometry.evenSplit(ContentRoi(0, 0, 700, 700), 0.99f),
            unknownCount = 0,
            confidence = 1f,
            boardConfidence = VisionThresholds.MIN_BOARD_CONFIDENCE,
            gridConfidence = VisionThresholds.MIN_GRID_CONFIDENCE,
            validation = ValidationResult.Pass,
            method = GridMethod.EVEN_SPLIT,
        )
    }
}
