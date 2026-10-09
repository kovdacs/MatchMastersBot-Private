package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.overlay.OverlayBoardGate
import com.match3vision.analyzer.overlay.OverlayPlacement
import org.junit.Test

/**
 * Phone Test 0 of 0.24.6.1. shot_ingame_chip.jpg is the native 1080×2400
 * screenshot with the collapsed chip. It is not the MediaProjection frame
 * that scored lattice 2.47. The full-screen fallback must HOLD.
 */
class DeviceTest061LiveRoiTest {

    private val pipeline = VisionPipeline()

    @Test
    fun ingameChip_boardBounds_andLatticeStillFits() {
        val frame = load("shot_ingame_chip.jpg")
        val result = pipeline.analyze(frame.pixels, frame.width, frame.height)
        val roi = result.grid.boardRoi
        println(
            "ROI_061 ingame roi=LTRB(${roi.left},${roi.top},${roi.right},${roi.bottom}) " +
                "snap=${result.diagnostics["playfieldSnap"]} " +
                "lattice=${result.diagnostics["latticeScore"]} " +
                "used=${result.diagnostics["latticeRoiUsed"]} " +
                "grid=${result.gridConfidence} board=${result.boardConfidence} " +
                "unk=${result.unknownCount} pass=${result.validation.isPass}",
        )
        assertThat(roi.left).isAtMost(15)
        assertThat(roi.right).isAtLeast(1065)
        assertThat(roi.top).isAtLeast(1184 - 15)
        assertThat(roi.top).isAtMost(1184 + 15)
        assertThat(roi.bottom).isAtLeast(2227 - 15)
        assertThat(roi.bottom).isAtMost(2227 + 15)
        assertThat(result.diagnostics["playfieldSnap"]).isEqualTo("gutter_lattice")
        assertThat(result.diagnostics["latticeRoiUsed"]).isEqualTo("yes")
        assertThat(result.diagnostics["latticeScore"]!!.toFloat())
            .isAtLeast(BoardFinder.LATTICE_MIN_SCORE)
        assertThat(result.diagnostics["overlayColumns"]).isEqualTo("none")
        assertThat(result.diagnostics["roiPlausible"]).isEqualTo("yes")
        val phoneChip = OverlayPlacement.Rect(650, 73, 1059, 325)
        val placed = OverlayPlacement.collapsedChipPx(1080, 2400, 2.75f)
        val board = OverlayPlacement.Rect(roi.left, roi.top, roi.right, roi.bottom)
        assertThat(phoneChip.intersects(board)).isFalse()
        assertThat(placed.intersects(board)).isFalse()
        val phoneGate = OverlayBoardGate.evaluate(
            overlay = phoneChip,
            boardLeft = roi.left,
            boardTop = roi.top,
            boardRight = roi.right,
            boardBottom = roi.bottom,
            frameWidth = 1080,
            frameHeight = 2400,
            screenWidth = 1080,
            screenHeight = 2400,
            rotation = 0,
        )
        assertThat(phoneGate.allowAnalysis).isTrue()
        val bundle = com.match3vision.analyzer.input.DiagnosticBundle.fromObservation(
            appVersion = "0.24.7",
            versionCode = 21,
            sourceCommit = "fixture",
            diagnosticTimestampMs = 1_700L,
            vision = result,
            screen = com.match3vision.analyzer.capture.ScreenMeasurement(
                widthPx = 1080,
                heightPx = 2400,
                densityDpi = 420,
                rotation = 0,
                source = com.match3vision.analyzer.capture.ScreenMeasurement.SOURCE_MAXIMUM_WINDOW,
            ),
            frameWidth = frame.width,
            frameHeight = frame.height,
            frameSequence = 5L,
            captureTimestampMs = 1_700L,
            frameAgeMs = 40L,
            frameElapsedMs = 40L,
            cadence = null,
            accessibilityConnected = false,
            gestureCapability = "connected=false",
            captureOn = true,
            hasFrame = true,
            moveAnalysis = "not run — vision gate",
            selectedMove = "none",
            coordinateReason = "coordinate origin alignment UNPROVEN",
            coordinateRefused = false,
            dispatchStatus = "NOT STARTED",
            callbackOutcome = "not dispatched",
            verificationStatus = "PENDING",
            verificationReason = "",
            simulated = true,
            analysisOnly = true,
        )
        println("CYCLE_061 ${bundle.cycleLine()}")
    }

    @Test
    fun afterStopSelfUi_fullScreenRoi_isImplausibleHold() {
        val frame = load("shot_after_stop.jpg")
        val result = pipeline.analyze(frame.pixels, frame.width, frame.height)
        val roi = result.grid.boardRoi
        val aspect = roi.height().toFloat() / roi.width().toFloat()
        println(
            "ROI_061 afterStop roi=LTRB(${roi.left},${roi.top},${roi.right},${roi.bottom}) " +
                "aspect=$aspect grid=${result.gridConfidence} unk=${result.unknownCount} " +
                "validation=${result.validation}",
        )
        assertThat(roi.top).isEqualTo(0)
        assertThat(aspect).isGreaterThan(RoiPlausibility.MAX_ASPECT)
        assertThat(result.gridConfidence).isAtLeast(VisionThresholds.MIN_GRID_CONFIDENCE)
        assertThat(result.validation.isPass).isFalse()
        val hold = result.validation as ValidationResult.Hold
        assertThat(hold.reason).isEqualTo(RoiPlausibility.HOLD_REASON)
    }

    @Test
    fun gridConfidenceAlone_doesNotPass_andFullScreenRoiIsRejected() {
        val highGridManyUnknown = VisionValidator().validate(
            boardConfidence = 0.99f,
            gridConfidence = 0.99f,
            unknownCount = 15,
        )
        assertThat(highGridManyUnknown.isPass).isFalse()
        assertThat(RoiPlausibility.reject(2400, 0, 1080, 2318)).isTrue()
        assertThat(RoiPlausibility.reject(2400, 1184, 1080, 1043)).isFalse()
        assertThat(RoiPlausibility.MAX_ASPECT).isEqualTo(1.3f)
        assertThat(VisionThresholds.MIN_GRID_CONFIDENCE).isEqualTo(0.98f)
    }

    @Test
    fun pvpGolden_unchanged() {
        val frame = RealFrameLoader.loadFromResource() ?: error("pvp_board.jpg missing")
        val result = pipeline.analyze(frame.pixels, frame.width, frame.height)
        assertThat(result.gridConfidence).isWithin(5e-4f).of(0.9872f)
        assertThat(result.diagnostics["playfieldSnap"]).isEqualTo("separator_square")
        assertThat(result.diagnostics["latticeRoiUsed"]).isEqualTo("no")
        assertThat(result.validation.isPass).isTrue()
    }

    private fun load(name: String) =
        RealFrameLoader.loadFromResource("real_frames/device_test0_0.24.6.1/$name")
            ?: error("missing $name")
}
