package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.vision.TileColor
import org.junit.Test

class AutoCalibrationTest {
    @Test
    fun provenGeometry_isOnlyTheKnownPhoneFrame() {
        assertThat(AutoCalibration.provenGeometry(1080, 2400, 1080, 2400, 0)).isTrue()
        assertThat(AutoCalibration.provenGeometry(1080, 2400, 1080, 2400, 1)).isFalse()
        assertThat(AutoCalibration.provenGeometry(2400, 1080, 2400, 1080, 0)).isFalse()
        assertThat(AutoCalibration.provenGeometry(1080, 2400, 1079, 2400, 0)).isFalse()
    }

    @Test
    fun changedRegion_mustOverlapTheSwappedRowAndColumn() {
        val before = latin()
        val after = latin()
        after[3][2] = TileColor.B
        after[3][4] = TileColor.Y
        val changed = AutoCalibration.changedCells(Board.fromColors(before), Board.fromColors(after))
        assertThat(AutoCalibration.overlaps(changed, 3, 2, 3, 3)).isTrue()
        assertThat(AutoCalibration.overlaps(changed, 6, 0, 6, 1)).isFalse()
        assertThat(AutoCalibration.overlaps(emptySet(), 3, 2, 3, 3)).isFalse()
    }

    @Test
    fun moveText_parsesTheSwap_andTheSavedNoteIsTheProbe() {
        val parsed = AutoCalibration.parseMove("(3,2)↔(3,3)")
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.toList()).containsExactly(3, 2, 3, 3).inOrder()
        val record = AutoCalibration.record(1_000L)
        assertThat(record.reason).isEqualTo("auto: move-1 verified")
        assertThat(record.alignmentProven).isFalse()
        assertThat(record.status).isEqualTo(CoordinateSelfCheck.STATUS_MEASURED_WITHIN_TOLERANCE)
    }

    @Test
    fun probeMiss_stopsAndDoesNotCountTheMove() {
        val session = FiveMoveSession()
        session.arm(0L)
        session.enableAutoProbe()
        val permit = (session.requestDispatch(gates(selfCheck = false)) as FiveMoveSession.Decision.Go).permit
        assertThat(session.consumePermit(permit)).isTrue()
        session.noteGesture(gesture())
        val stopped = session.onSettle(passSample(hash = 99L, overlaps = false))
        assertThat(stopped).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat((stopped as FiveMoveSession.Decision.Stop).reason).isEqualTo(AutoCalibration.STOP_MISSED)
        assertThat(session.verifiedCount).isEqualTo(0)
        assertThat(session.takeAutoSave()).isFalse()
        assertThat(session.requestDispatch(gates(selfCheck = false))).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
    }

    @Test
    fun probeHit_countsTheMove_andAsksToSaveCalibration() {
        val session = FiveMoveSession()
        session.arm(0L)
        session.enableAutoProbe()
        val permit = (session.requestDispatch(gates(selfCheck = false)) as FiveMoveSession.Decision.Go).permit
        session.consumePermit(permit)
        session.noteGesture(gesture())
        val held = session.onSettle(passSample(hash = 99L, overlaps = true))
        assertThat(held).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.verifiedCount).isEqualTo(1)
        assertThat(session.takeAutoSave()).isTrue()
        assertThat(session.report()).contains("auto: move-1 verified")
        assertThat(session.requestDispatch(gates(selfCheck = false))).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
    }

    private fun latin(): Array<Array<TileColor>> {
        val palette = listOf(TileColor.R, TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O)
        return Array(7) { row -> Array(7) { col -> palette[(row + col) % 6] } }
    }

    private fun gates(selfCheck: Boolean) = FiveMoveSession.Gates(
        nowMs = 1_000L,
        a11yConnected = true,
        selfCheckMeasured = selfCheck,
        overlayCollapsed = true,
        overlayOutsideRoi = true,
        visionPass = true,
        frameFresh = true,
        ownUi = false,
        msSinceCollapse = FiveMoveSession.MIN_POST_COLLAPSE_MS,
        roiPlausible = true,
    )

    private fun gesture() = FiveMoveSession.GestureFact(
        startedAtMs = 1_000L,
        nowMs = 1_100L,
        callbackCompleted = true,
        cancelled = false,
        cells = "(3,2)↔(3,3)",
        fromX = 1f,
        fromY = 1f,
        toX = 2f,
        toY = 1f,
        beforeHash = 5L,
        beforeUnknown = 0,
    )

    private fun passSample(hash: Long, overlaps: Boolean) = FiveMoveSession.SettleSample(
        nowMs = 2_000L,
        boardHash = hash,
        diffFraction = 0f,
        frameFresh = true,
        roiPlausible = true,
        visionPass = true,
        unknownCount = 0,
        ownUi = false,
        a11yConnected = true,
        overlayOutside = true,
        capturedAfterGesture = true,
        swapOverlaps = overlaps,
    )
}
