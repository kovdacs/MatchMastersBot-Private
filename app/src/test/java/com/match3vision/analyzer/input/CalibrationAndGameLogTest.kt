package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.moves.Move
import com.match3vision.analyzer.moves.PlayMoveRanker
import com.match3vision.analyzer.vision.TileColor
import java.io.File
import java.time.ZoneOffset
import org.junit.Test

class CalibrationAndGameLogTest {
    private fun measured(atMs: Long = 1_700_000_000_000L): CoordinateSelfCheck.Record =
        CoordinateSelfCheck.Record(
            status = CoordinateSelfCheck.STATUS_MEASURED_WITHIN_TOLERANCE,
            expectedX = 540f,
            expectedY = 1080f,
            screenWidth = 1080,
            screenHeight = 2400,
            frameWidth = 1080,
            frameHeight = 2400,
            rotation = 0,
            statusBarInsetPx = 80,
            navigationBarInsetPx = 60,
            cutoutInsetPx = 80,
            originOffsetX = 0,
            originOffsetY = 0,
            observedNote = "raw touch",
            alignmentProven = false,
            reason = "within tolerance",
            observedX = 543f,
            observedY = 1080f,
            recordedAtMs = atMs,
        )

    private fun key(versionCode: Int = 29, rotation: Int = 0, density: Int = 420) = ScreenKey(
        screenWidth = 1080,
        screenHeight = 2400,
        rotation = rotation,
        densityDpi = density,
        versionCode = versionCode,
    )

    @Test
    fun savedHit_roundTrips_andReusesOnlyTheSameKey() {
        val saved = SavedCalibration(key(), measured())
        val file = File.createTempFile("calib", ".txt")
        val library = CalibrationLibrary()
        library.put(saved)
        library.write(file)
        val loaded = CalibrationLibrary.read(file).find(key())
        assertThat(loaded).isNotNull()
        assertThat(loaded!!.record.alignmentProven).isFalse()
        assertThat(loaded.record.observedX).isEqualTo(543f)
        assertThat(loaded.distancePx()).isWithin(0.1f).of(3f)
        assertThat(loaded.chip(ZoneOffset.UTC)).isEqualTo("kalibráció: mentett (2023-11-14 22:13, 3.0px)")
        assertThat(CalibrationLibrary.read(file).find(key(versionCode = 28))).isNull()
        assertThat(CalibrationLibrary.read(file).find(key(rotation = 1))).isNull()
        assertThat(CalibrationLibrary.read(file).find(key(density = 320))).isNull()
        file.delete()
    }

    @Test
    fun reuse_acceptsSavedHit_andRejectsADifferentScreen() {
        val record = measured(atMs = 1_000L)
        val saved = SavedCalibration(key(), record)
        val now = key()
        assertThat(
            CalibrationReuse.accepts(record, serviceWallMs = 5_000L, saved = saved, now = now),
        ).isTrue()
        assertThat(
            CalibrationReuse.accepts(
                record,
                serviceWallMs = 5_000L,
                saved = saved,
                now = key(versionCode = 30),
            ),
        ).isFalse()
        assertThat(
            CalibrationReuse.accepts(record, serviceWallMs = 5_000L, saved = null, now = now),
        ).isFalse()
        val fresh = measured(atMs = 6_000L)
        assertThat(
            CalibrationReuse.accepts(fresh, serviceWallMs = 5_000L, saved = null, now = now),
        ).isTrue()
        CoordinateSelfCheck.clear()
        CoordinateSelfCheck.restore(record)
        assertThat(CoordinateSelfCheck.current()!!.alignmentProven).isFalse()
        assertThat(CoordinateSelfCheck.allowsContinuousStart()).isTrue()
        CoordinateSelfCheck.clear()
    }

    @Test
    fun gameLog_keepsFailedMove_andComparesBlueWithoutPersonalData() {
        val beforeBoard = Board.fromColors(Array(7) { row ->
            Array(7) { col -> if (row == 0 && col < 3) TileColor.B else TileColor.R }
        })
        val afterBoard = Board.fromColors(Array(7) { row ->
            Array(7) { col -> if (row == 0 && col < 2) TileColor.B else TileColor.R }
        })
        val candidate = PlayMoveRanker.Candidate(
            move = Move(0, 2, 1, 2),
            matchLen = 4,
            extraMove = true,
            blueCleared = 2,
            totalCleared = 4,
            cascadeSteps = 1,
            uncertain = true,
            specials = "none",
            lowerRow = 1,
        )
        val pending = GameMoveLog.Pending(
            moveNumber = 2,
            sessionStartedAtMs = 1_000L,
            before = GameMoveLog.view(beforeBoard, 1_100L, 0.99f, 0.97f, 0),
            candidates = listOf(candidate),
            selected = candidate.move.toString(),
        )
        val line = GameMoveLog.finish(
            pending = pending,
            after = GameMoveLog.view(afterBoard, 1_800L, 0.99f, 0.96f, 0),
            verification = "FAILED — board unchanged",
            settleMs = 700L,
            elapsedMs = 800L,
            stopReason = "STOP — board unchanged after move 2",
            userInterference = false,
        )
        assertThat(line).contains("\"verification\":\"FAILED — board unchanged\"")
        assertThat(line).contains("\"extraMove\":true")
        assertThat(line).contains("\"predictedBlue\":2")
        assertThat(line).contains("\"actualBlueEstimate\":1")
        assertThat(line).contains("\"blueDelta\":-1")
        assertThat(line).contains("\"extraMoveGranted\":\"not detectable\"")
        assertThat(line).contains("\"extraMoveError\":\"not comparable\"")
        assertThat(line).contains("confirmed by the owner")
        assertThat(line).doesNotContain("account")
        assertThat(line).doesNotContain("@")
        val unfinished = GameMoveLog.unfinished(pending, "STOP — user interference", 2_000L)
        assertThat(unfinished).contains("\"verification\":\"UNFINISHED\"")
        assertThat(unfinished).contains("\"blueClearedEstimate\":null")
    }

    @Test
    fun sessionReport_includesTheRule_andTheJsonLog() {
        val session = FiveMoveSession()
        session.arm(1_000L)
        session.noteCalibration("kalibráció: mentett (2026-10-09 09:40, 3.0px)")
        val before = Board.fromColors(Array(7) { Array(7) { TileColor.R } })
        session.beginGameLog(
            GameMoveLog.Pending(
                moveNumber = 1,
                sessionStartedAtMs = 1_000L,
                before = GameMoveLog.view(before, 1_000L, 0.99f, 0.99f, 0),
                candidates = emptyList(),
                selected = "(0,0)↔(0,1)",
            ),
        )
        session.finishGameLog(
            after = null,
            verification = "CHANGED_UNSETTLED — gesture sent, board kept changing, not a stable PASS",
            settleMs = 20_000L,
            elapsedMs = 20_000L,
            stopReason = "STOP — per-move settle budget 20000ms",
            userInterference = false,
        )
        val report = session.report()
        assertThat(report).contains("confirmed by the owner")
        assertThat(report).contains("kalibráció: mentett (2026-10-09 09:40, 3.0px)")
        assertThat(report).contains("--- GAME LOG ---")
        assertThat(report).contains("personalData=none")
        assertThat(report).contains("CHANGED_UNSETTLED")
        assertThat(session.gameLogText()).contains("\"kind\":\"move\"")
    }
}
