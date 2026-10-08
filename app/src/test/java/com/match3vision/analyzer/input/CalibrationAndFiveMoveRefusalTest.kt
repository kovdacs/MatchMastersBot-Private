package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.overlay.BubbleModeCaption
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/**
 * 0.24.7.2: the calibration hit uses the on-screen target, analysis-only
 * does not stick when accessibility is already on, and 5 LÉPÉS refusals
 * are the exact Hungarian sentences.
 */
class CalibrationAndFiveMoveRefusalTest {

    @Test
    fun windowPlan_isFullScreenAtDisplayOrigin_withNoInsetFit() {
        val plan = CalibrationWindowPlan.plan(1080, 2400)
        assertThat(plan.x).isEqualTo(0)
        assertThat(plan.y).isEqualTo(0)
        assertThat(plan.width).isEqualTo(1080)
        assertThat(plan.height).isEqualTo(2400)
        assertThat(plan.fitInsetsTypes).isEqualTo(0)
        assertThat(plan.fitInsetsSides).isEqualTo(0)
        assertThat(plan.layoutInScreen).isTrue()
        assertThat(plan.layoutNoLimits).isTrue()
        assertThat(plan.cutoutAlways).isTrue()
        assertThat(CalibrationTarget.RING_RADIUS_PX).isAtLeast(120f)
        assertThat(CalibrationTarget.LABEL).isEqualTo("IDE ÉRINTS")
    }

    @Test
    fun shiftedWindow_hitOnTheDrawnCircle_passes_nominalComparisonFails() {
        CoordinateSelfCheck.clear()
        val nominal = CalibrationTouch.expectedPoint(1080, 2400)
        assertThat(nominal.first).isEqualTo(540f)
        assertThat(nominal.second).isWithin(1f).of(1080f)
        // Owner screenshot: the ring sat about 85 px below the nominal point.
        val statusBarShift = 85
        val onScreen = CalibrationTarget.onScreenPoint(0, statusBarShift, nominal.first, nominal.second)
        assertThat(onScreen.first).isEqualTo(540f)
        assertThat(onScreen.second).isEqualTo(nominal.second + statusBarShift)

        val hit = CalibrationTouch.recordRawTouch(
            rawX = onScreen.first,
            rawY = onScreen.second,
            screenWidth = 1080,
            screenHeight = 2400,
            frameWidth = 1080,
            frameHeight = 2400,
            rotation = 0,
            targetX = onScreen.first,
            targetY = onScreen.second,
        )
        assertThat(hit.status).isEqualTo(CoordinateSelfCheck.STATUS_MEASURED_WITHIN_TOLERANCE)
        assertThat(hit.alignmentProven).isFalse()

        val againstNominal = CalibrationTouch.recordRawTouch(
            rawX = onScreen.first,
            rawY = onScreen.second,
            screenWidth = 1080,
            screenHeight = 2400,
            frameWidth = 1080,
            frameHeight = 2400,
            rotation = 0,
        )
        assertThat(againstNominal.status).isEqualTo(CoordinateSelfCheck.STATUS_OBSERVED_MISMATCH)
        assertThat(statusBarShift).isGreaterThan(CoordinateSelfCheck.MEASURED_TOLERANCE_PX.toInt())
    }

    @Test
    fun phoneTap_225_1745_isFarFromTheDrawnCircle() {
        CoordinateSelfCheck.clear()
        val nominal = CalibrationTouch.expectedPoint(1080, 2400)
        val onScreen = CalibrationTarget.onScreenPoint(0, 85, nominal.first, nominal.second)
        val rawX = 225f
        val rawY = 1745f
        val fromDrawn = CalibrationTarget.distance(rawX, rawY, onScreen.first, onScreen.second)
        val fromNominal = hypot(rawX - nominal.first, rawY - nominal.second)
        assertThat(fromDrawn).isGreaterThan(600f)
        assertThat(fromNominal).isGreaterThan(700f)
        val rec = CalibrationTouch.recordRawTouch(
            rawX = rawX,
            rawY = rawY,
            screenWidth = 1080,
            screenHeight = 2400,
            frameWidth = 1080,
            frameHeight = 2400,
            rotation = 0,
            targetX = onScreen.first,
            targetY = onScreen.second,
        )
        assertThat(rec.status).isEqualTo(CoordinateSelfCheck.STATUS_OBSERVED_MISMATCH)
        assertThat(rec.alignmentProven).isFalse()
        assertThat(CalibrationTarget.resultLine(false, fromDrawn)).contains("eltérés")
        assertThat(CalibrationTarget.resultLine(true, 0f)).contains("KALIBRÁCIÓ OK")
    }

    @Test
    fun fiveMoveRefusals_areTheExactHungarianSentences() {
        val (ctrl, exec) = controller()
        assertThat(ctrl.armFiveMoveTest(0L, selfCheckThisSession = true, a11yConnected = false))
            .isFalse()
        assertThat(ctrl.lastReason).isEqualTo(FiveMoveArm.NEED_A11Y)
        assertThat(ctrl.armFiveMoveTest(0L, selfCheckThisSession = false, a11yConnected = true))
            .isFalse()
        assertThat(ctrl.lastReason).isEqualTo(FiveMoveArm.NEED_CALIBRATION)
        assertThat(ctrl.armFiveMoveTest(0L, selfCheckThisSession = true, a11yConnected = true, boardVisible = false))
            .isFalse()
        assertThat(ctrl.lastReason).isEqualTo(FiveMoveArm.NEED_BOARD)
        assertThat(ctrl.enableSwitch().isEnabled()).isFalse()
        assertThat(exec.dispatched).isEmpty()
        assertThat(ctrl.analysisOnly).isFalse()
    }

    @Test
    fun chipTitles_showMode() {
        assertThat(BubbleModeCaption.title(false, "5 LÉPÉS 0/5", false)).isEqualTo("ELEMZÉS")
        assertThat(BubbleModeCaption.title(false, "5 LÉPÉS 0/5", true)).isEqualTo("KALIBRÁCIÓ OK")
        assertThat(BubbleModeCaption.title(true, "5 LÉPÉS 2/5", true)).isEqualTo("5 LÉPÉS 2/5")
    }

    @Test
    fun calibrationFrames_areExcludedLikeOwnUi() {
        assertThat(
            LiveFrameFilter.blocked(
                ownUi = false,
                calibrationVisible = true,
                calibrationDismissWallMs = 0L,
                frameTimestampMs = 5_000L,
            ),
        ).isTrue()
        assertThat(
            LiveFrameFilter.blocked(
                ownUi = false,
                calibrationVisible = false,
                calibrationDismissWallMs = 10_000L,
                frameTimestampMs = 11_000L,
            ),
        ).isTrue()
        assertThat(
            LiveFrameFilter.blocked(
                ownUi = false,
                calibrationVisible = false,
                calibrationDismissWallMs = 10_000L,
                frameTimestampMs = 12_000L,
            ),
        ).isFalse()
        assertThat(LiveFrameFilter.frameSource(false, true))
            .isEqualTo(DiagnosticBundle.SOURCE_CALIBRATION)
        val history = DiagnosticHistory()
        history.admitLive(bundle(1_000L), frame(), ownUi = true, pastTransition = true, plausibleRoi = true)
        assertThat(history.latestLive()).isNull()
        assertThat(history.ringSnapshot()).isEmpty()
    }

    @Test
    fun stalePin_version21_isDropped_andNewSessionClearsPin() {
        val dir = File.createTempFile("pin", "dir")
        dir.delete()
        dir.mkdirs()
        File(dir, "pinned-first-hold.json").writeText(
            "{ \"versionCode\": 21, \"diagnosticTimestampMs\": 1 }\n",
        )
        File(dir, "pinned-first-hold.png").writeBytes(byteArrayOf(1, 2, 3))
        DiagnosticHistoryStore.replaceForTest(DiagnosticHistory(), null)
        try {
            DiagnosticHistoryStore.install(dir, versionCode = 23)
            assertThat(File(dir, "pinned-first-hold.json").exists()).isFalse()
            assertThat(File(dir, "pinned-first-hold.png").exists()).isFalse()
            assertThat(DiagnosticHistoryStore.snapshot().pinnedJson).isNull()

            File(dir, "pinned-first-hold.json").writeText(
                "{ \"versionCode\": 23, \"diagnosticTimestampMs\": 9 }\n",
            )
            DiagnosticHistoryStore.install(dir, versionCode = 23)
            assertThat(DiagnosticHistoryStore.snapshot().pinnedJson).contains("\"versionCode\": 23")
            DiagnosticHistoryStore.clearPinForNewSession()
            assertThat(DiagnosticHistoryStore.snapshot().pinnedJson).isNull()
            assertThat(File(dir, "pinned-first-hold.json").exists()).isFalse()
        } finally {
            DiagnosticHistoryStore.replaceForTest(DiagnosticHistory(), null)
        }
    }

    private fun controller(): Pair<AutoPlayController, RecordingInputGestureExecutor> {
        CoordinateSelfCheck.clear()
        val exec = RecordingInputGestureExecutor(ready = true)
        val sw = InputEnableSwitch.disabledByDefault()
        val ctrl = AutoPlayController(
            enableSwitch = sw,
            inputLoop = InputLoopController(
                inputEngine = AutomaticInputEngine(enableSwitch = sw, executor = exec),
            ),
        )
        assertThat(
            ctrl.onDiagnosticStart(captureReady = true, overlayReady = true, a11yConnected = true),
        ).isTrue()
        return ctrl to exec
    }

    private fun bundle(stamp: Long) = DiagnosticBundle.fromObservation(
        appVersion = "0.24.7.2",
        versionCode = 23,
        sourceCommit = "test",
        diagnosticTimestampMs = stamp,
        vision = null,
        screen = com.match3vision.analyzer.capture.ScreenMeasurement(
            widthPx = 100,
            heightPx = 100,
            rotation = 0,
            source = com.match3vision.analyzer.capture.ScreenMeasurement.SOURCE_MAXIMUM_WINDOW,
        ),
        frameWidth = 100,
        frameHeight = 100,
        frameSequence = stamp,
        captureTimestampMs = stamp,
        frameAgeMs = 10L,
        frameElapsedMs = 10L,
        cadence = null,
        accessibilityConnected = true,
        gestureCapability = "test",
        captureOn = true,
        hasFrame = true,
        moveAnalysis = "none",
        selectedMove = "none",
        coordinateReason = "",
        coordinateRefused = false,
        dispatchStatus = "NOT STARTED",
        callbackOutcome = "not dispatched",
        verificationStatus = VerificationPolicy.PENDING,
        verificationReason = "hold",
        simulated = false,
    )

    private fun frame() = DiagnosticFrame.render(
        pixels = null,
        width = 2,
        height = 2,
        roiLeft = 0,
        roiTop = 0,
        roiRight = 2,
        roiBottom = 2,
        xBoundaries = null,
        yBoundaries = null,
    )
}
