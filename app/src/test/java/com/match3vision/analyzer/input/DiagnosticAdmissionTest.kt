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

class DiagnosticAdmissionTest {

    @Test
    fun ownUi_andTransition_stayOutOfRingPinnedAndBest() {
        val history = DiagnosticHistory(capacity = 8)
        history.admitLive(bundle(stamp = 5_000L, unknown = 0), frame(), ownUi = true, pastTransition = true, plausibleRoi = true)
        history.admitLive(bundle(stamp = 6_000L, unknown = 0), frame(), ownUi = false, pastTransition = false, plausibleRoi = true)
        assertThat(history.ringSnapshot()).isEmpty()
        assertThat(history.pinnedFirstHold).isNull()
        assertThat(history.bestInGame).isNull()
        assertThat(history.latestLive()).isNull()
    }

    @Test
    fun ring_isTimeSpread_andLatestIsSeparate() {
        val history = DiagnosticHistory(capacity = 3)
        val admitted = longArrayOf(0L, 1_000L, 20_000L, 40_000L, 60_000L)
        admitted.forEach { stamp ->
            history.admitLive(
                bundle(stamp = stamp, unknown = 1, hold = false),
                frame(),
                ownUi = false,
                pastTransition = true,
                plausibleRoi = true,
            )
        }
        assertThat(history.ringSnapshot().map { it.bundle.captureTimestampMs })
            .containsExactly(0L, 20_000L, 60_000L)
            .inOrder()
        assertThat(history.latestLive()!!.bundle.captureTimestampMs).isEqualTo(60_000L)
        assertThat(history.latest()!!.bundle.captureTimestampMs).isEqualTo(60_000L)
    }

    @Test
    fun best_prefersPlausibleRoi_thenLowestUnknown() {
        val history = DiagnosticHistory()
        history.admitLive(bundle(stamp = 10_000L, unknown = 0, hold = true), frame(), false, true, plausibleRoi = false)
        history.admitLive(bundle(stamp = 30_000L, unknown = 3, hold = false), frame(), false, true, plausibleRoi = true)
        assertThat(history.bestInGame!!.bundle.unknownCount).isEqualTo(3)
        history.admitLive(bundle(stamp = 50_000L, unknown = 1, hold = false), frame(), false, true, plausibleRoi = true)
        assertThat(history.bestInGame!!.bundle.captureTimestampMs).isEqualTo(50_000L)
        history.admitLive(bundle(stamp = 70_000L, unknown = 2, hold = false), frame(), false, true, plausibleRoi = true)
        assertThat(history.bestInGame!!.bundle.unknownCount).isEqualTo(1)
        val text = DiagnosticExportText.render(history)
        assertThat(text).contains("BEST IN-GAME")
        assertThat(text).contains("playfieldSnap=")
        assertThat(text).contains("latticeScore=")
        assertThat(text).contains("unk=1")
        assertThat(text).contains("best-in-game.png")
    }

    @Test
    fun share_includesBestAndFiveMovePngs() {
        val history = DiagnosticHistory()
        val dir = File.createTempFile("bestshare", "dir")
        dir.delete()
        dir.mkdirs()
        val wide = DiagnosticFrame.render(
            pixels = IntArray(1_000 * 2_000) { 0xFF336699.toInt() },
            width = 1_000,
            height = 2_000,
            roiLeft = 10,
            roiTop = 20,
            roiRight = 900,
            roiBottom = 1_800,
            xBoundaries = floatArrayOf(10f, 900f),
            yBoundaries = floatArrayOf(20f, 1_800f),
            maxEdge = 1_200,
        )
        assertThat(wide.height).isEqualTo(1_200)
        assertThat(wide.width).isEqualTo(600)
        history.admitLive(bundle(stamp = 20_000L, unknown = 0, hold = false), wide, false, true, true)
        history.setFiveMoveReport("measuredSessionMs=9000\nmove=1 durationMs=1800")
        DiagnosticFiles.write(dir, history)
        File(dir, "five-move").mkdirs()
        File(dir, "five-move/move-01-before.png").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "five-move/move-01-after.png").writeBytes(byteArrayOf(4, 5, 6))
        val names = DiagnosticFiles.shareAttachments(dir).map { it.name }
        assertThat(names).contains("best-in-game.png")
        assertThat(names).contains("move-01-before.png")
        assertThat(names).contains("move-01-after.png")
        assertThat(File(dir, "export.txt").readText()).contains("measuredSessionMs=9000")
    }

    @Test
    fun stability_isTheFractionOfChangedSamples() {
        val pixels = IntArray(20 * 20) { 0xFF000000.toInt() }
        val before = BoardStability.signature(pixels, 20, 20, 0, 0, 20, 20)
        val after = pixels.copyOf()
        after[0] = 0xFFFFFFFF.toInt()
        val changed = BoardStability.signature(after, 20, 20, 0, 0, 20, 20)
        assertThat(BoardStability.changedFraction(before, before)).isEqualTo(0f)
        assertThat(BoardStability.changedFraction(before, changed)).isGreaterThan(0f)
        assertThat(BoardStability.changedFraction(before, changed))
            .isLessThan(FiveMoveSession.STABLE_FRACTION)
    }

    private fun frame() = DiagnosticFrame.render(
        pixels = intArrayOf(1, 2, 3, 4),
        width = 2,
        height = 2,
        roiLeft = 0,
        roiTop = 0,
        roiRight = 2,
        roiBottom = 2,
        xBoundaries = floatArrayOf(0f, 2f),
        yBoundaries = floatArrayOf(0f, 2f),
    )

    private fun bundle(stamp: Long, unknown: Int, hold: Boolean = true): DiagnosticBundle {
        val vision = VisionResult(
            board = VisionBoard(Array(7) { Array(7) { cell() } }),
            grid = GridGeometry.evenSplit(ContentRoi(0, 0, 70, 70), 0.99f),
            unknownCount = unknown,
            confidence = 1f,
            boardConfidence = 0.99f,
            gridConfidence = 0.99f,
            validation = if (hold) {
                ValidationResult.Hold("HOLD: test")
            } else {
                ValidationResult.Pass
            },
            method = GridMethod.EVEN_SPLIT,
            diagnostics = mapOf(
                "latticeScore" to "0.80",
                "latticeRoiUsed" to "no",
                "playfieldSnap" to "separator_square",
            ),
        )
        return DiagnosticBundle.fromObservation(
            appVersion = "0.24.7.1",
            versionCode = 22,
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
        )
    }

    private fun cell() = CellVision(
        color = TileColor.B,
        shape = TileShape.CIRCLE,
        special = SpecialType.NONE,
        occluded = false,
        confidence = 1f,
        isUnknown = false,
    )
}
