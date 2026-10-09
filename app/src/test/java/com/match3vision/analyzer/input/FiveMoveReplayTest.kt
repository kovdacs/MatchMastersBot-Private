package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.capture.ContentRoi
import com.match3vision.analyzer.vision.RealFrameLoader
import com.match3vision.analyzer.vision.ValidationResult
import com.match3vision.analyzer.vision.VisionPipeline
import com.match3vision.analyzer.vision.VisionResult
import org.junit.Test
import java.io.File

/**
 * Checked-in screenshots and exports through the production vision pipeline
 * and the 5-move session. No gate is changed. A frame that is not a stable
 * PASS cannot verify a move. A flagged touch cannot turn a later board
 * change into a verified move.
 */
class FiveMoveReplayTest {

    @Test
    fun everyCheckedInImage_isClassified_andANonPassDoesNotVerify() {
        val shots = loadShots()
        assertThat(shots.map { it.name }).contains("real_frames/pvp_board.jpg")
        shots.forEach { shot ->
            println(
                "REPLAY ${shot.name} ${shot.width}x${shot.height} " +
                    "gate=${if (shot.qualifies) "PASS" else "HOLD"} " +
                    "unk=${shot.vision.unknownCount} roi=${shot.roiPlausible} " +
                    "hash=${shot.hash}",
            )
        }
        val primary = shots.single { it.name == "real_frames/pvp_board.jpg" }
        assertThat(primary.qualifies).isTrue()
        val transients = shots.filter { !it.qualifies }
        assertThat(transients).isNotEmpty()
        transients.forEach { shot ->
            val session = armedSession()
            val decision = session.onSettle(sampleFrom(shot, nowMs = 500L, diffFraction = 0.01f))
            assertThat(decision).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
            assertThat((decision as FiveMoveSession.Decision.Hold).reason).startsWith("settling —")
            assertThat(session.verifiedCount).isEqualTo(0)
            assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.SETTLING)
            session.abort("STOP — replay", 600L)
            assertThat(session.movesSnapshot().single().ignoredTransient).isEqualTo(1)
        }
    }

    @Test
    fun realHoldThenRealPass_verifiesOnlyWhenTheBoardChanged_andTheNextMoveWaits() {
        val shots = loadShots()
        val pass = shots.single { it.name == "real_frames/pvp_board.jpg" }
        val animation = shots.single { it.name == "real_frames/pvp_board_activate_fx.jpg" }
        assertThat(pass.qualifies).isTrue()
        assertThat(animation.qualifies).isFalse()
        val session = armedSession(beforeHash = animation.hash)
        assertThat(session.onSettle(sampleFrom(animation, nowMs = 1_200L, diffFraction = 0.4f)))
            .isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.verifiedCount).isEqualTo(0)
        if (pass.hash == animation.hash) {
            val unchanged = session.onSettle(sampleFrom(pass, nowMs = 1_000L + FiveMoveSession.UNCHANGED_MIN_MS, diffFraction = 0.01f))
            assertThat(unchanged).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
            assertThat(session.verifiedCount).isEqualTo(0)
            return
        }
        val verified = session.onSettle(sampleFrom(pass, nowMs = 2_000L, diffFraction = 0.01f, frameSequence = 8L))
        assertThat(verified).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.verifiedCount).isEqualTo(1)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(session.movesSnapshot().single().ignoredTransient).isAtLeast(1)
        assertThat(session.requestDispatch(gates(nowMs = 2_100L, frameSequence = 8L)))
            .isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        val next = session.requestDispatch(gates(nowMs = 2_200L, frameSequence = 9L))
        assertThat(next).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
        assertThat((next as FiveMoveSession.Decision.Go).permit.moveNumber).isEqualTo(2)
    }

    @Test
    fun realBoardChange_afterAFlaggedTouch_isNotAutomaticSuccess() {
        val shots = loadShots()
        val pass = shots.single { it.name == "real_frames/pvp_board.jpg" }
        val other = shots.single { it.name == "real_frames/pvp_board_activate_fx.jpg" }
        assertThat(other.hash).isNotEqualTo(pass.hash)
        val session = armedSession(beforeHash = other.hash)
        session.onSettle(sampleFrom(other, nowMs = 1_100L, diffFraction = 0.5f))
        assertThat(session.noteOutsideTouch(1_300L)).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        val manual = session.onSettle(sampleFrom(pass, nowMs = 1_800L, diffFraction = 0.01f))
        assertThat(manual).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(session.verifiedCount).isEqualTo(0)
        assertThat(session.gesturesDispatched).isEqualTo(1)
        val report = session.report()
        assertThat(report).contains("user interference")
        assertThat(report).contains("cannot tell our gesture from a finger on the glass")
        assertThat(report).doesNotContain("PASS — callback completed")
    }

    @Test
    fun recordedExports_areNotTreatedAsAVerifiedMove() {
        val root = File("src/test/resources/real_frames")
        val exports = root.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        assertThat(exports.map { it.name }).containsAtLeast(
            "pvp_board_android_export.json",
            "hold_export_latest.json",
        )
        val hold = exports.single { it.name == "hold_export_latest.json" }.readText()
        assertThat(hold).contains("\"visionGate\": \"HOLD\"")
        assertThat(hold).contains("\"unknownCount\": 19")
        val session = armedSession()
        val decision = session.onSettle(
            FiveMoveSession.SettleSample(
                nowMs = 800L,
                boardHash = 19L,
                diffFraction = 0.01f,
                frameFresh = true,
                roiPlausible = true,
                visionPass = false,
                unknownCount = 19,
                ownUi = false,
                a11yConnected = true,
            ),
        )
        assertThat(decision).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat((decision as FiveMoveSession.Decision.Hold).reason).contains("vision HOLD unk=19")
        assertThat(session.verifiedCount).isEqualTo(0)
        session.abort("STOP — replay", 900L)
        assertThat(session.movesSnapshot().single().ignoredReasons).contains("vision HOLD unk=19")
    }

    private data class Shot(
        val name: String,
        val width: Int,
        val height: Int,
        val vision: VisionResult,
        val hash: Long,
        val roiPlausible: Boolean,
        val qualifies: Boolean,
    )

    private fun loadShots(): List<Shot> {
        val root = File("src/test/resources/real_frames")
        assertThat(root.isDirectory).isTrue()
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            .sortedBy { it.path }
            .toList()
        assertThat(files).isNotEmpty()
        val pipeline = VisionPipeline()
        return files.map { file ->
            val loaded = RealFrameLoader.loadFromFile(file) ?: error("decode failed: ${file.path}")
            val vision = pipeline.analyze(
                loaded.pixels,
                loaded.width,
                loaded.height,
                ContentRoi.full(loaded.width, loaded.height),
            )
            val held = vision.validation as? ValidationResult.Hold
            val roi = vision.diagnostics["roiPlausible"] != "no" &&
                held?.reason?.contains("ROI IMPLAUSIBLE") != true
            val qualifies = vision.validation.isPass &&
                vision.unknownCount <= FiveMoveSession.MAX_UNKNOWN &&
                roi
            Shot(
                name = file.relativeTo(File("src/test/resources")).path.replace('\\', '/'),
                width = loaded.width,
                height = loaded.height,
                vision = vision,
                hash = Board.fromVision(vision.board).contentHash(),
                roiPlausible = roi,
                qualifies = qualifies,
            )
        }
    }

    private fun armedSession(beforeHash: Long = 11L): FiveMoveSession {
        val session = FiveMoveSession()
        session.arm(0L)
        val permit = (session.requestDispatch(gates(nowMs = 100L)) as FiveMoveSession.Decision.Go).permit
        assertThat(session.consumePermit(permit)).isTrue()
        session.noteGesture(
            FiveMoveSession.GestureFact(
                startedAtMs = 100L,
                nowMs = 200L,
                callbackCompleted = true,
                cancelled = false,
                cells = "(0,4)↔(1,4)",
                fromX = 693.5f,
                fromY = 1261f,
                toX = 693.5f,
                toY = 1411f,
                beforeHash = beforeHash,
                beforeUnknown = 0,
            ),
        )
        return session
    }

    private fun sampleFrom(
        shot: Shot,
        nowMs: Long,
        diffFraction: Float?,
        frameSequence: Long = 0L,
    ) = FiveMoveSession.SettleSample(
        nowMs = nowMs,
        boardHash = shot.hash,
        diffFraction = diffFraction,
        frameFresh = true,
        roiPlausible = shot.roiPlausible,
        visionPass = shot.vision.validation.isPass,
        unknownCount = shot.vision.unknownCount,
        ownUi = false,
        a11yConnected = true,
        overlayOutside = true,
        capturedAfterGesture = true,
        frameSequence = frameSequence,
    )

    private fun gates(nowMs: Long, frameSequence: Long = 0L) = FiveMoveSession.Gates(
        nowMs = nowMs,
        a11yConnected = true,
        selfCheckMeasured = true,
        overlayCollapsed = true,
        overlayOutsideRoi = true,
        visionPass = true,
        frameFresh = true,
        ownUi = false,
        msSinceCollapse = FiveMoveSession.MIN_POST_COLLAPSE_MS,
        roiPlausible = true,
        frameSequence = frameSequence,
    )
}
