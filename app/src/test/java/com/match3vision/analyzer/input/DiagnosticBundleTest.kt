package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import com.match3vision.analyzer.capture.FrameCadence
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
import org.junit.Test
import java.io.File

class DiagnosticBundleTest {

    @Test
    fun holdBundle_explainsVision_andDoesNotInventAMove() {
        val vision = held()
        val cadence = FrameCadence()
        cadence.record(1_000L)
        cadence.record(1_200L)
        cadence.record(1_450L)
        val bundle = DiagnosticBundle.fromObservation(
            appVersion = "0.24.4-production-safety-integration",
            versionCode = 16,
            sourceCommit = "test-commit",
            diagnosticTimestampMs = 1_700_000_000_000L,
            vision = vision,
            screen = ScreenMeasurement(
                widthPx = 1080,
                heightPx = 2400,
                rotation = 0,
                source = ScreenMeasurement.SOURCE_MAXIMUM_WINDOW,
            ),
            frameWidth = 1080,
            frameHeight = 2400,
            frameSequence = 9L,
            captureTimestampMs = 1_700_000_000_100L,
            frameAgeMs = 180L,
            frameElapsedMs = 40_000L,
            cadence = cadence,
            accessibilityConnected = true,
            gestureCapability = "connected=true capabilityBit=true",
            captureOn = true,
            hasFrame = true,
            moveAnalysis = "should-not-appear",
            selectedMove = "0,0→0,1",
            coordinateReason = "",
            coordinateRefused = false,
            dispatchStatus = "SUCCESS",
            callbackOutcome = "onCompleted",
            verificationStatus = VerificationPolicy.PENDING,
            verificationReason = "",
            simulated = true,
        )
        assertThat(bundle.failureClass).isEqualTo(DiagnosticBundle.CLASS_VISION)
        assertThat(bundle.visionGate).isEqualTo("HOLD")
        assertThat(bundle.visionReason).contains("grid confidence")
        assertThat(bundle.moveAnalysis).contains("not run")
        assertThat(bundle.selectedMove).isEqualTo("none")
        assertThat(bundle.dispatchStatus).isEqualTo("NOT STARTED")
        assertThat(bundle.verificationStatus).isNotEqualTo(VerificationPolicy.SUCCESS)
        assertThat(bundle.coordinateAlignmentProven).isFalse()
        assertThat(bundle.screenSource).isEqualTo(ScreenMeasurement.SOURCE_MAXIMUM_WINDOW)
        assertThat(bundle.frameWidth).isEqualTo(1080)
        assertThat(bundle.screenWidth).isEqualTo(1080)
        assertThat(bundle.cadence).contains("medianMs=250")
        assertThat(bundle.cellLabels).hasSize(49)
        val json = bundle.toJson()
        assertThat(json).contains("\"failureClass\": \"VISION\"")
        assertThat(json).contains("grid confidence")
        assertThat(json).doesNotContain("VERIFY SUCCESS")
        assertThat(json).contains("\"simulated\": true")
        assertThat(json).contains("coordinate origin alignment UNPROVEN")
        val file = File.createTempFile("diag", ".json")
        DiagnosticBundle.write(file, bundle)
        assertThat(file.readText()).contains("\"sourceCommit\": \"test-commit\"")
        DiagnosticExport.publish(bundle)
        assertThat(DiagnosticExport.latestJson).contains("VISION")
    }

    @Test
    fun captureOff_isNotLabeledAsVisionFailure() {
        val kind = DiagnosticBundle.classify(
            captureOn = false,
            hasFrame = false,
            accessibilityConnected = true,
            coordinateRefused = false,
            visionPass = false,
            dispatchFailed = false,
            verificationFailed = false,
        )
        assertThat(kind).isEqualTo(DiagnosticBundle.CLASS_CAPTURE)
        assertThat(
            DiagnosticBundle.classify(
                captureOn = true,
                hasFrame = true,
                accessibilityConnected = false,
                coordinateRefused = false,
                visionPass = false,
                dispatchFailed = false,
                verificationFailed = false,
            ),
        ).isEqualTo(DiagnosticBundle.CLASS_ACCESSIBILITY)
        assertThat(
            DiagnosticBundle.classify(
                captureOn = true,
                hasFrame = true,
                accessibilityConnected = true,
                coordinateRefused = true,
                visionPass = true,
                dispatchFailed = false,
                verificationFailed = false,
            ),
        ).isEqualTo(DiagnosticBundle.CLASS_COORDINATE)
    }

    private fun held(): VisionResult {
        val cells = Array(7) { Array(7) { cell(TileColor.B) } }
        return VisionResult(
            board = VisionBoard(cells),
            grid = GridGeometry.evenSplit(ContentRoi(10, 20, 700, 900), 0.97f),
            unknownCount = 4,
            confidence = 0.4f,
            boardConfidence = 0.90f,
            gridConfidence = 0.97f,
            validation = ValidationResult.Hold("HOLD: grid confidence 0.970 < 0.980"),
            method = GridMethod.PROJECTION,
        )
    }

    private fun cell(color: TileColor) = CellVision(
        color = color,
        shape = TileShape.CIRCLE,
        special = SpecialType.NONE,
        occluded = false,
        confidence = 1f,
        isUnknown = false,
    )
}
