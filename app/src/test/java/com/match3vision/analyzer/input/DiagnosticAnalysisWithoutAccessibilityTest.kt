package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import com.match3vision.analyzer.capture.ScreenMeasurement
import com.match3vision.analyzer.vision.RealFrameLoader
import com.match3vision.analyzer.vision.VisionPipeline
import org.junit.Test

/**
 * Accessibility off still analyzes and exports. It does not dispatch.
 * Continuous INDÍTÁS stays locked until a recorded self-check.
 * A recorded point that is not the expected point does not unlock EGY LÉPÉS.
 * None of this sets alignmentProven.
 */
class DiagnosticAnalysisWithoutAccessibilityTest {

    @Test
    fun gate_a11yOff_analyzesAndDoesNotCallThePlayCycle() {
        val off = DiagnosticAnalysisGate.decide(
            captureOn = true,
            hasFrame = true,
            analysisOnly = true,
            a11yConnected = false,
        )
        assertThat(off.analyzeAndExport).isTrue()
        assertThat(off.callRunCycle).isFalse()
        assertThat(off.reason).contains("no touch")

        val play = DiagnosticAnalysisGate.decide(
            captureOn = true,
            hasFrame = true,
            analysisOnly = false,
            a11yConnected = true,
        )
        assertThat(play.analyzeAndExport).isTrue()
        assertThat(play.callRunCycle).isTrue()

        val noFrame = DiagnosticAnalysisGate.decide(
            captureOn = false,
            hasFrame = false,
            analysisOnly = true,
            a11yConnected = false,
        )
        assertThat(noFrame.analyzeAndExport).isFalse()
        assertThat(noFrame.callRunCycle).isFalse()
    }

    @Test
    fun diagnosticStart_runsAnalysisExport_andNeverDispatches() {
        CoordinateSelfCheck.clear()
        CaptureOverlayTrace.clear()
        val exec = RecordingInputGestureExecutor(ready = true)
        val sw = InputEnableSwitch.disabledByDefault()
        val ctrl = AutoPlayController(
            enableSwitch = sw,
            inputLoop = InputLoopController(
                inputEngine = AutomaticInputEngine(enableSwitch = sw, executor = exec),
            ),
        )
        assertThat(ctrl.onStartRequested(a11yConnected = false)).isFalse()
        assertThat(ctrl.mode).isNotEqualTo(AutoPlayController.Mode.RUNNING)
        assertThat(ctrl.lastReason).contains("ACCESSIBILITY")

        assertThat(ctrl.onDiagnosticStart(captureReady = true, overlayReady = true)).isTrue()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.RUNNING)
        assertThat(ctrl.analysisOnly).isTrue()
        assertThat(ctrl.enableSwitch().isEnabled()).isFalse()

        val frame = RealFrameLoader.loadFromResource()
            ?: error("pvp_board.jpg missing")
        val vision = VisionPipeline().analyze(
            frame.pixels,
            frame.width,
            frame.height,
            ContentRoi.full(frame.width, frame.height),
        )
        val cycle = ctrl.runCycleIfActive(vision, null)
        assertThat(cycle).isNotNull()
        assertThat(cycle!!.gestureStatus).isEqualTo("NOT CREATED")
        assertThat(cycle.reason).contains("no touch")
        assertThat(cycle.executed).isNull()
        assertThat(exec.dispatched).isEmpty()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.RUNNING)
        assertThat(ctrl.moveCount).isEqualTo(0)

        CaptureOverlayTrace.collapsed = true
        CaptureOverlayTrace.overlayRect = "LTRB(629,77,1058,341)"
        CaptureOverlayTrace.gateResult = "overlay outside board ROI"
        CaptureOverlayTrace.skippedAfterCollapse = 2
        CaptureOverlayTrace.collapseWallMs = 1_000L
        CaptureOverlayTrace.analyzedFrameTimestampMs = frameTimestamp
        CaptureOverlayTrace.gestureStatus = "CREATED"
        CaptureOverlayTrace.dispatchState = "SUCCESS"
        val bundle = DiagnosticBundle.fromObservation(
            appVersion = "0.24.6.1",
            versionCode = 20,
            sourceCommit = "test",
            diagnosticTimestampMs = 5_000L,
            vision = vision,
            screen = ScreenMeasurement(
                widthPx = frame.width,
                heightPx = frame.height,
                rotation = 0,
                source = ScreenMeasurement.SOURCE_MAXIMUM_WINDOW,
            ),
            frameWidth = frame.width,
            frameHeight = frame.height,
            frameSequence = 4L,
            captureTimestampMs = frameTimestamp,
            frameAgeMs = 40L,
            frameElapsedMs = 40L,
            cadence = null,
            accessibilityConnected = false,
            gestureCapability = "connected=false",
            captureOn = true,
            hasFrame = true,
            moveAnalysis = "must-not-run",
            selectedMove = "0,0→0,1",
            coordinateReason = "",
            coordinateRefused = false,
            dispatchStatus = "SUCCESS",
            callbackOutcome = "onCompleted",
            verificationStatus = VerificationPolicy.PENDING,
            verificationReason = "diagnostic",
            simulated = false,
            analysisOnly = true,
        )
        assertThat(bundle.accessibilityConnected).isFalse()
        assertThat(bundle.frameWidth).isEqualTo(1080)
        assertThat(bundle.frameHeight).isEqualTo(2400)
        assertThat(bundle.frameAgeMs).isEqualTo(40L)
        assertThat(bundle.dispatchStatus).isEqualTo("NOT STARTED")
        assertThat(bundle.gestureStatus).isEqualTo("NOT CREATED")
        assertThat(bundle.analysisOnly).isTrue()
        assertThat(bundle.callbackOutcome).isEqualTo("not dispatched")
        assertThat(bundle.coordinateAlignmentProven).isFalse()
        assertThat(bundle.overlayCollapsed).isTrue()
        assertThat(bundle.overlayRect).isEqualTo("LTRB(629,77,1058,341)")
        assertThat(bundle.overlayGateResult).contains("outside")
        assertThat(bundle.skippedFrameCount).isEqualTo(2)
        assertThat(bundle.collapseWallMs).isEqualTo(1_000L)
        assertThat(bundle.analyzedFrameTimestampMs).isEqualTo(frameTimestamp)
        assertThat(bundle.analyzedFrameTimestampMs).isGreaterThan(bundle.collapseWallMs)
        assertThat(bundle.latticeScore).isNotEqualTo("not measured")
        assertThat(bundle.latticeStd).isNotEqualTo("not measured")
        assertThat(bundle.playfieldSnap).isEqualTo("separator_square")
        assertThat(bundle.latticeRoiUsed).isEqualTo("no")
        assertThat(bundle.roiAspect).isNotEqualTo("not measured")
        assertThat(bundle.roiTopFraction).isNotEqualTo("not measured")
        assertThat(bundle.roiBottomMarginPx).isNotEqualTo("not measured")
        assertThat(bundle.pitchX).contains(",")
        assertThat(bundle.pitchY).contains(",")
        assertThat(bundle.overlayMaskColumns).isNotEqualTo("not measured")
        assertThat(bundle.columnPitchMaxMinRatio).isNotEqualTo("not measured")
        assertThat(bundle.rowPitchMaxMinRatio).isNotEqualTo("not measured")
        assertThat(bundle.columnPitchMaxMinRatio.toFloat()).isAtLeast(1f)
        assertThat(bundle.rowPitchMaxMinRatio.toFloat()).isAtLeast(1f)
        assertThat(bundle.specialCropSizes).contains("x")
        assertThat(bundle.specialRejectedCropCount).isAtLeast(0)
        val json = bundle.toJson()
        for (field in EXPORT_FIELDS) {
            assertThat(json).contains("\"$field\"")
        }
        assertThat(json).contains("\"accessibilityConnected\": false")
        assertThat(json).contains("\"dispatchStatus\": \"NOT STARTED\"")
        assertThat(json).contains("\"gestureStatus\": \"NOT CREATED\"")
        assertThat(json).contains("\"frameWidth\": 1080")
        assertThat(json).contains("\"frameAgeMs\": 40")
        assertThat(json).doesNotContain("VERIFY SUCCESS")
        assertThat(exec.dispatched).isEmpty()
    }

    @Test
    fun continuousStart_requiresRecordedSelfCheck() {
        CoordinateSelfCheck.clear()
        val ctrl = AutoPlayController()
        assertThat(CoordinateSelfCheck.allowsContinuousStart()).isFalse()
        assertThat(
            ctrl.onStartRequested(a11yConnected = true, captureReady = true, overlayReady = true),
        ).isFalse()
        assertThat(ctrl.mode).isNotEqualTo(AutoPlayController.Mode.RUNNING)
        assertThat(ctrl.enableSwitch().isEnabled()).isFalse()
        assertThat(ctrl.lastReason).contains("self-check")
        assertThat(ctrl.lastReason).contains("NOT proven")
        assertThat(ctrl.runCycleIfActive(com.match3vision.analyzer.vision.VisionResult(
            board = com.match3vision.analyzer.vision.VisionBoard(
                Array(7) {
                    Array(7) {
                        com.match3vision.analyzer.vision.CellVision.unknown()
                    }
                },
            ),
            grid = com.match3vision.analyzer.vision.GridGeometry.evenSplit(
                ContentRoi(0, 0, 70, 70),
                0.5f,
            ),
            unknownCount = 49,
            confidence = 0f,
            boardConfidence = 0f,
            gridConfidence = 0.5f,
            validation = com.match3vision.analyzer.vision.ValidationResult.Hold("HOLD"),
            method = com.match3vision.analyzer.vision.GridMethod.EVEN_SPLIT,
        ))).isNull()
    }

    @Test
    fun observedMismatch_doesNotUnlockSingleMove() {
        CoordinateSelfCheck.clear()
        val rec = CoordinateSelfCheck.record(
            expectedX = 100f,
            expectedY = 200f,
            screenWidth = 1080,
            screenHeight = 2400,
            frameWidth = 1080,
            frameHeight = 2400,
            rotation = 0,
            observedX = 200f,
            observedY = 200f,
        )
        assertThat(rec.status).isEqualTo(CoordinateSelfCheck.STATUS_OBSERVED_MISMATCH)
        assertThat(rec.alignmentProven).isFalse()
        assertThat(rec.observedX).isEqualTo(200f)
        assertThat(CoordinateSelfCheck.allowsSingleMoveArm()).isFalse()
        assertThat(CoordinateSelfCheck.allowsContinuousStart()).isFalse()
        val ctrl = AutoPlayController()
        assertThat(
            ctrl.armSingleMove(a11yConnected = true, captureReady = true, overlayReady = true),
        ).isFalse()
        assertThat(ctrl.mode).isNotEqualTo(AutoPlayController.Mode.RUNNING)
        assertThat(ctrl.enableSwitch().isEnabled()).isFalse()
        assertThat(ctrl.lastReason).contains("EGY LÉPÉS")
        assertThat(ctrl.lastReason).contains("OBSERVED_MISMATCH")
        assertThat(
            ctrl.onStartRequested(a11yConnected = true, captureReady = true, overlayReady = true),
        ).isFalse()
        assertThat(ctrl.lastReason).contains("NOT proven")
    }

    private companion object {
        const val frameTimestamp = 1_700L
        val EXPORT_FIELDS = listOf(
            "overlayCollapsed",
            "overlayRect",
            "overlayGateResult",
            "skippedFrameCount",
            "collapseWallMs",
            "analyzedFrameTimestampMs",
            "latticeScore",
            "latticeStd",
            "playfieldSnap",
            "latticeRoiUsed",
            "latticeCandidate",
            "roiAspect",
            "roiTopFraction",
            "roiBottomMarginPx",
            "pitchX",
            "pitchY",
            "specialCropSizes",
            "specialRejectedCropCount",
            "specialRejectedCropOrigin",
            "gestureStatus",
            "analysisOnly",
            "overlayMaskColumns",
            "frameSizeRetained",
            "columnPitchMaxMinRatio",
            "rowPitchMaxMinRatio",
            "frameWidth",
            "frameHeight",
            "frameAgeMs",
            "accessibilityConnected",
            "dispatchStatus",
        )
    }
}
