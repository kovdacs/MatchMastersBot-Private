package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
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
import com.match3vision.analyzer.vision.VisionResult
import org.junit.Test
import java.io.File

class DiagnosticHistoryTest {

    @Test
    fun ring_keepsLastN_andDoesNotDropPinnedFirstHold() {
        val history = DiagnosticHistory(capacity = 3)
        val dir = File.createTempFile("diag", "dir")
        dir.delete()
        dir.mkdirs()
        val first = entry(hold = true, stamp = 100L)
        history.record(first.bundle, first.frame)
        repeat(5) { index ->
            val later = entry(hold = false, stamp = 200L + index)
            history.record(later.bundle, later.frame)
        }
        DiagnosticFiles.write(dir, history)
        assertThat(history.ringSnapshot()).hasSize(3)
        assertThat(history.ringSnapshot().map { it.bundle.diagnosticTimestampMs })
            .containsExactly(202L, 203L, 204L)
            .inOrder()
        assertThat(history.pinnedFirstHold!!.bundle.diagnosticTimestampMs).isEqualTo(100L)
        assertThat(history.pinnedJson).contains("\"diagnosticTimestampMs\": 100")
        assertThat(File(dir, "pinned-first-hold.json").readText()).contains("\"diagnosticTimestampMs\": 100")
        assertThat(File(dir, "latest.json").readText()).contains("\"diagnosticTimestampMs\": 204")
        assertThat(File(dir, "pinned-first-hold.json").readText())
            .doesNotContain("\"diagnosticTimestampMs\": 204")
        val reloaded = DiagnosticHistory(capacity = 3)
        reloaded.adoptPinnedJson(File(dir, "pinned-first-hold.json").readText())
        reloaded.record(entry(hold = true, stamp = 999L).bundle, missingFrame())
        assertThat(reloaded.pinnedJson).contains("\"diagnosticTimestampMs\": 100")
        assertThat(reloaded.pinnedJson).doesNotContain("\"diagnosticTimestampMs\": 999")
    }

    @Test
    fun ringExport_listsEveryEntry_andAttachesEveryFramePng() {
        val history = DiagnosticHistory(capacity = 8)
        val dir = File.createTempFile("diagring", "dir")
        dir.delete()
        dir.mkdirs()
        val pixels = intArrayOf(
            0xFFFF0000.toInt(),
            0xFF00FF00.toInt(),
            0xFF0000FF.toInt(),
            0xFFFFFFFF.toInt(),
        )
        repeat(8) { index ->
            val frame = DiagnosticFrame.render(
                pixels = pixels,
                width = 2,
                height = 2,
                roiLeft = 0,
                roiTop = 0,
                roiRight = 2,
                roiBottom = 2,
                xBoundaries = floatArrayOf(0f, 2f),
                yBoundaries = floatArrayOf(0f, 2f),
            )
            assertThat(frame.png).isNotNull()
            val stamp = 1_000L + index
            history.record(bundle(hold = true, stamp = stamp, export = frame), frame)
        }
        DiagnosticFiles.write(dir, history)
        val text = File(dir, "export.txt").readText()
        assertThat(text).contains("RING 8/8")
        assertThat(text).contains("does not prove a longer run")
        for (index in 0 until 8) {
            assertThat(text).contains("seq=${1_000L + index}")
            assertThat(text).contains("latticeScore=")
            assertThat(text).contains("latticeRoiUsed=")
            assertThat(text).contains("overlayGate=")
            assertThat(text).contains("frameAge=")
            assertThat(text).contains("frame=100x100")
            assertThat(File(dir, "ring/frame-%02d.png".format(index)).isFile).isTrue()
        }
        val attachments = DiagnosticFiles.shareAttachments(dir)
        assertThat(attachments.map { it.name }).contains("export.txt")
        assertThat(attachments.count { it.name.endsWith(".png") }).isAtLeast(8)
        println(text)
    }

    @Test
    fun restart_reloadsCaptureStateStopReasonBoosterAttemptsAndRing() {
        val history = DiagnosticHistory()
        val dir = File.createTempFile("diagresume", "dir")
        dir.delete()
        dir.mkdirs()
        val pixels = IntArray(4) { 0xFF00FFFF.toInt() }
        val frame = DiagnosticFrame.render(
            pixels = pixels,
            width = 2,
            height = 2,
            roiLeft = 0,
            roiTop = 0,
            roiRight = 2,
            roiBottom = 2,
            xBoundaries = floatArrayOf(0f, 2f),
            yBoundaries = floatArrayOf(0f, 2f),
        )
        history.noteRuntime("ON", "STOP — freeze")
        history.setFiveMoveReport(
            "--- 10 LÉPÉS TESZT ---\n--- BOOSTER ---\n" +
                "attempt=1 point=(180,940) durationMs=120 result=sent\n",
        )
        history.record(bundle(hold = false, stamp = 4_200L, export = frame), frame)
        DiagnosticFiles.write(dir, history)
        assertThat(File(dir, "session-resume.txt").readText()).contains("attempt=1 point=(180,940)")
        val fresh = DiagnosticHistory()
        DiagnosticFiles.loadInto(fresh, dir)
        val text = DiagnosticExportText.render(fresh)
        assertThat(text).contains("captureState=ON")
        assertThat(text).contains("lastStopReason=STOP — freeze")
        assertThat(text).contains("attempt=1 point=(180,940)")
        assertThat(text).contains("durationMs=120")
        assertThat(fresh.ringSnapshot()).hasSize(1)
        assertThat(text).contains("seq=4200")
        DiagnosticFiles.write(dir, fresh)
        assertThat(File(dir, "ring/frame-00.png").isFile).isTrue()
        DiagnosticHistoryStore.replaceForTest(DiagnosticHistory(), null)
        try {
            DiagnosticHistoryStore.install(dir, versionCode = -1)
            val exported = DiagnosticHistoryStore.exportText()
            assertThat(exported).contains("captureState=ON")
            assertThat(exported).contains("lastStopReason=STOP — freeze")
            assertThat(exported).contains("result=sent")
            val once = DiagnosticHistoryStore.ringCount()
            DiagnosticHistoryStore.install(dir, versionCode = -1)
            assertThat(DiagnosticHistoryStore.ringCount()).isEqualTo(once)
            DiagnosticHistoryStore.clearPinForNewSession()
            assertThat(DiagnosticHistoryStore.exportText()).contains("attempt=1 point=(180,940)")
        } finally {
            DiagnosticHistoryStore.replaceForTest(DiagnosticHistory(), null)
        }
    }

    @Test
    fun explicitClear_dropsPinnedFirstHold() {
        val history = DiagnosticHistory(capacity = 4)
        val dir = File.createTempFile("diagclear", "dir")
        dir.delete()
        dir.mkdirs()
        history.record(entry(hold = true, stamp = 1L).bundle, missingFrame())
        DiagnosticFiles.write(dir, history)
        assertThat(File(dir, "pinned-first-hold.json").isFile).isTrue()
        history.clear()
        DiagnosticFiles.clear(dir)
        assertThat(history.pinnedFirstHold).isNull()
        assertThat(history.pinnedJson).isNull()
        assertThat(dir.exists()).isFalse()
    }

    @Test
    fun missingPixels_sayNotExported_andDoNotInventAFrame() {
        val frame = DiagnosticFrame.render(
            pixels = null,
            width = 10,
            height = 10,
            roiLeft = 0,
            roiTop = 0,
            roiRight = 10,
            roiBottom = 10,
            xBoundaries = null,
            yBoundaries = null,
            refusal = "NOT EXPORTED — bitmap recycled before copy",
        )
        assertThat(frame.status).isEqualTo(DiagnosticFrame.STATUS_NOT_EXPORTED)
        assertThat(frame.png).isNull()
        assertThat(frame.pixels).isNull()
        assertThat(frame.reason).contains("recycled")
        val bundle = bundle(hold = true, stamp = 5L, export = frame)
        assertThat(bundle.frameExportStatus).isEqualTo(DiagnosticFrame.STATUS_NOT_EXPORTED)
        assertThat(bundle.toJson()).contains("NOT EXPORTED")
        assertThat(bundle.toJson()).doesNotContain("VERIFY SUCCESS")
        assertThat(bundle.diagnosticInfluencesGate).isFalse()
    }

    @Test
    fun overlay_isDrawnOnTheSuppliedFrame_notAPlaceholder() {
        val width = 20
        val height = 20
        val red = IntArray(width * height) { 0xFFFF0000.toInt() }
        val blue = IntArray(width * height) { 0xFF0000FF.toInt() }
        val roi = ContentRoi(4, 4, 16, 16)
        val redOut = DiagnosticFrame.render(
            pixels = red,
            width = width,
            height = height,
            roiLeft = roi.left,
            roiTop = roi.top,
            roiRight = roi.right,
            roiBottom = roi.bottom,
            xBoundaries = floatArrayOf(4f, 16f),
            yBoundaries = floatArrayOf(4f, 16f),
        )
        val blueOut = DiagnosticFrame.render(
            pixels = blue,
            width = width,
            height = height,
            roiLeft = roi.left,
            roiTop = roi.top,
            roiRight = roi.right,
            roiBottom = roi.bottom,
            xBoundaries = floatArrayOf(4f, 16f),
            yBoundaries = floatArrayOf(4f, 16f),
        )
        assertThat(redOut.status).isEqualTo(DiagnosticFrame.STATUS_EXPORTED)
        val redPixels = redOut.pixels!!
        val bluePixels = blueOut.pixels!!
        assertThat(redPixels[10 * width + 10]).isEqualTo(0xFFFF0000.toInt())
        assertThat(bluePixels[10 * width + 10]).isEqualTo(0xFF0000FF.toInt())
        assertThat(redPixels[4 * width + 8]).isEqualTo(DiagnosticFrame.OVERLAY)
        assertThat(redOut.png!![0]).isEqualTo(0x89.toByte())
        assertThat(redOut.png!![1]).isEqualTo('P'.code.toByte())
        val decoded = com.match3vision.analyzer.vision.RealFrameLoader.decode(
            redOut.png!!.inputStream(),
            "overlay",
        )
        assertThat(decoded.width).isEqualTo(width)
        assertThat(decoded.height).isEqualTo(height)
        assertThat(decoded.pixels[10 * width + 10] and 0xFFFFFF).isEqualTo(0xFF0000)
    }

    @Test
    fun recordingAHold_doesNotChangeTheVisionGate() {
        val vision = heldVision()
        val before = vision.validation
        val bundle = DiagnosticBundle.fromObservation(
            appVersion = "0.24.5-diagnostics-single-move",
            versionCode = 17,
            sourceCommit = "test",
            diagnosticTimestampMs = 50L,
            vision = vision,
            screen = ScreenMeasurement(1080, 2400, source = ScreenMeasurement.SOURCE_MAXIMUM_WINDOW),
            frameWidth = 8,
            frameHeight = 8,
            frameSequence = 1L,
            captureTimestampMs = 40L,
            frameAgeMs = 10L,
            frameElapsedMs = 10L,
            cadence = null,
            accessibilityConnected = true,
            gestureCapability = "test",
            captureOn = true,
            hasFrame = true,
            moveAnalysis = "should-not-run",
            selectedMove = "0,0→0,1",
            coordinateReason = "",
            coordinateRefused = false,
            dispatchStatus = "SUCCESS",
            callbackOutcome = "onCompleted — callback completed; not VERIFY SUCCESS",
            verificationStatus = VerificationPolicy.SUCCESS,
            verificationReason = "",
            simulated = true,
        )
        val history = DiagnosticHistory()
        history.record(bundle, missingFrame())
        assertThat(vision.validation).isEqualTo(before)
        assertThat(vision.validation.isPass).isFalse()
        assertThat(bundle.visionGate).isEqualTo("HOLD")
        assertThat(bundle.verificationStatus).isNotEqualTo(VerificationPolicy.SUCCESS)
        assertThat(bundle.finalSafetyDecision).doesNotContain("VERIFY SUCCESS")
        assertThat(bundle.diagnosticInfluencesGate).isFalse()
        assertThat(bundle.selectedMove).isEqualTo("none")
        assertThat(bundle.toJson()).contains("diagnosticInfluencesGate")
        assertThat(DiagnosticExportText.render(history)).contains("PINNED FIRST HOLD")
        assertThat(DiagnosticLuminance.measure(IntArray(16) { 0 }).text).contains("BLACK FRAME")
        assertThat(DiagnosticLuminance.measure(null).text).contains("not measured")
    }

    private fun entry(hold: Boolean, stamp: Long) =
        DiagnosticHistory.Entry(bundle(hold, stamp, missingFrame()), missingFrame())

    private fun missingFrame() = DiagnosticFrame.render(
        pixels = null,
        width = 0,
        height = 0,
        roiLeft = 0,
        roiTop = 0,
        roiRight = 0,
        roiBottom = 0,
        xBoundaries = null,
        yBoundaries = null,
    )

    private fun bundle(hold: Boolean, stamp: Long, export: DiagnosticFrame.Export): DiagnosticBundle {
        val vision = if (hold) heldVision() else passVision()
        return DiagnosticBundle.fromObservation(
            appVersion = "0.24.5",
            versionCode = 17,
            sourceCommit = "test",
            diagnosticTimestampMs = stamp,
            vision = vision,
            screen = ScreenMeasurement(100, 100, source = ScreenMeasurement.SOURCE_MAXIMUM_WINDOW),
            frameWidth = 100,
            frameHeight = 100,
            frameSequence = stamp,
            captureTimestampMs = stamp,
            frameAgeMs = 1L,
            frameElapsedMs = 1L,
            cadence = null,
            accessibilityConnected = true,
            gestureCapability = "test",
            captureOn = true,
            hasFrame = true,
            moveAnalysis = "none",
            selectedMove = "none",
            coordinateReason = "UNPROVEN",
            coordinateRefused = false,
            dispatchStatus = "NOT STARTED",
            callbackOutcome = "not dispatched",
            verificationStatus = VerificationPolicy.PENDING,
            verificationReason = "",
            simulated = true,
            meanLuminance = "not measured",
            blackFrame = "not measured — frame pixels were not supplied",
            frameExportStatus = export.status,
            frameExportReason = export.reason,
        )
    }

    private fun heldVision(): VisionResult = VisionResult(
        board = VisionBoard(Array(7) { Array(7) { cell(TileColor.B) } }),
        grid = GridGeometry.evenSplit(ContentRoi(0, 0, 70, 70), 0.97f),
        unknownCount = 4,
        confidence = 0.4f,
        boardConfidence = 0.90f,
        gridConfidence = 0.97f,
        validation = ValidationResult.Hold("HOLD: grid confidence 0.970 < 0.980"),
        method = GridMethod.PROJECTION,
        diagnostics = mapOf("projRelVarX" to "0.0100", "projRelVarY" to "0.0120", "projPeakCountX" to "6", "projPeakCountY" to "6"),
    )

    private fun passVision(): VisionResult = heldVision().copy(
        validation = ValidationResult.Pass,
        gridConfidence = 0.99f,
        boardConfidence = 0.99f,
        unknownCount = 0,
    )

    private fun cell(color: TileColor) = CellVision(
        color = color,
        shape = TileShape.CIRCLE,
        special = SpecialType.NONE,
        occluded = false,
        confidence = 1f,
        isUnknown = false,
    )
}
