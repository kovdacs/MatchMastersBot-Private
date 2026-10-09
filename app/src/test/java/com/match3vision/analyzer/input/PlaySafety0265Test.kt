package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.hud.HudObservation
import com.match3vision.analyzer.moves.PlayMoveRanker
import com.match3vision.analyzer.vision.TileColor
import org.junit.Test

/** Regression checks for the 0.26.5 settle, booster, and ownership rules. */
class PlaySafety0265Test {
    @Test
    fun boosterTap_doesNotCountAsASwipe_andLeavesTheProbeOpen() {
        val session = FiveMoveSession()
        session.arm(0L)
        session.enableAutoProbe()
        session.recordBooster(
            changed = false,
            stable = false,
            callbackCompleted = true,
            x = 80f,
            y = 920f,
            nowMs = 50L,
            playExport = "booster",
        )
        assertThat(session.swipesDispatched).isEqualTo(0)
        assertThat(session.gesturesDispatched).isEqualTo(1)
        assertThat(session.verifiedCount).isEqualTo(0)
        assertThat(session.needsGeometryCheck()).isTrue()
        assertThat(session.requestDispatch(gates(selfCheck = false)))
            .isInstanceOf(FiveMoveSession.Decision.Go::class.java)
    }

    @Test
    fun oneStableFrame_doesNotVerify_andTheNextSwipeWaitsForADoubleRead() {
        val session = FiveMoveSession()
        session.arm(0L)
        val permit = (session.requestDispatch(gates(selfCheck = true)) as FiveMoveSession.Decision.Go).permit
        session.consumePermit(permit)
        session.noteGesture(gesture(beforeHash = 4L))
        val early = session.onSettle(pass(nowMs = 2_000L, hash = 9L))
        assertThat(early).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.verifiedCount).isEqualTo(0)
        session.onSettle(pass(nowMs = 2_300L, hash = 9L))
        val done = session.onSettle(pass(nowMs = 2_600L, hash = 9L))
        assertThat(done).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.verifiedCount).isEqualTo(1)
        assertThat(session.requestDispatch(gates(selfCheck = true, nowMs = 3_000L)))
            .isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        session.noteFreshBoard(9L, fresh = true, pass = true)
        assertThat(session.requestDispatch(gates(selfCheck = true, nowMs = 3_100L)))
            .isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        session.noteFreshBoard(9L, fresh = true, pass = true)
        assertThat(session.requestDispatch(gates(selfCheck = true, nowMs = 3_200L)))
            .isInstanceOf(FiveMoveSession.Decision.Go::class.java)
    }

    @Test
    fun changedCellsOffTheSwap_doNotVerify() {
        val session = FiveMoveSession()
        session.arm(0L)
        val permit = (session.requestDispatch(gates(selfCheck = true)) as FiveMoveSession.Decision.Go).permit
        session.consumePermit(permit)
        session.noteGesture(gesture(beforeHash = 4L))
        session.onSettle(pass(nowMs = 2_000L, hash = 9L, supported = false))
        session.onSettle(pass(nowMs = 2_300L, hash = 9L, supported = false))
        val stopped = session.onSettle(pass(nowMs = 2_600L, hash = 9L, supported = false))
        assertThat(stopped).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(session.verifiedCount).isEqualTo(0)
        assertThat(session.stopReason).contains("swapped rows")
    }

    @Test
    fun soloUnreadTurn_neverStops_andTwoUnreadPvpTurnsDo() {
        val solo = FiveMoveSession()
        solo.arm(0L)
        assertThat(
            solo.noteVerifiedTurn(HudObservation.NOT_DETECTABLE, soloPositive = true, nowMs = 10L),
        ).isNull()
        assertThat(
            solo.noteVerifiedTurn(HudObservation.NOT_DETECTABLE, soloPositive = true, nowMs = 20L),
        ).isNull()
        assertThat(solo.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)

        val pvp = FiveMoveSession()
        pvp.arm(0L)
        assertThat(
            pvp.noteVerifiedTurn(HudObservation.NOT_DETECTABLE, soloPositive = false, nowMs = 10L),
        ).isNull()
        val stopped = pvp.noteVerifiedTurn(HudObservation.NOT_DETECTABLE, soloPositive = false, nowMs = 20L)
        assertThat(stopped).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(pvp.stopReason).contains("turn not readable")
    }

    @Test
    fun boosterTargetHold_blocksSwipes_untilActivateIsGoneOrTheHoldExpires() {
        val session = FiveMoveSession()
        session.arm(0L)
        session.beginBoosterTargetHold(1_000L)
        val holding = session.pollBoosterTarget(nowMs = 2_000L, activateWord = true, stablePass = true)
        assertThat(holding).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.requestDispatch(gates(selfCheck = true, nowMs = 2_100L)))
            .isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.pollBoosterTarget(nowMs = 3_000L, activateWord = false, stablePass = true)).isNull()
        assertThat(session.boosterHolding).isFalse()

        val expired = FiveMoveSession()
        expired.arm(0L)
        expired.beginBoosterTargetHold(1_000L)
        val stopped = expired.pollBoosterTarget(
            nowMs = 1_000L + FiveMoveSession.BOOSTER_TARGET_HOLD_MS,
            activateWord = true,
            stablePass = false,
        )
        assertThat(stopped).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(expired.stopReason).contains("booster needs a target")
    }

    @Test
    fun noLegalMove_retriesThreeTimesASecondApart_thenStops() {
        val session = FiveMoveSession()
        session.arm(0L)
        assertThat(session.noteNoLegalMove(1_000L)).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.noteNoLegalMove(1_200L)).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.noteNoLegalMove(2_000L)).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        val stopped = session.noteNoLegalMove(3_000L)
        assertThat(stopped).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(session.stopReason).contains("no legal")
    }

    @Test
    fun supports_acceptsACellOnTheSwappedRow_withoutRequiringBothAxes() {
        val before = latin()
        val after = latin()
        after[3][6] = TileColor.B
        val changed = AutoCalibration.changedCells(Board.fromColors(before), Board.fromColors(after))
        assertThat(AutoCalibration.supports(changed, 3, 2, 3, 3)).isTrue()
        assertThat(AutoCalibration.overlaps(changed, 3, 2, 3, 3)).isFalse()
        assertThat(AutoCalibration.supports(changed, 0, 0, 1, 1)).isFalse()
    }

    @Test
    fun alternatingPixelDiff_withIdenticalLabels_verifies() {
        val session = FiveMoveSession()
        session.arm(0L)
        val permit = (session.requestDispatch(gates(selfCheck = true, nowMs = 1_000L)) as FiveMoveSession.Decision.Go).permit
        session.consumePermit(permit)
        session.noteGesture(gesture(beforeHash = 1L))
        val diffs = floatArrayOf(0.45f, 0.02f, 0.45f, 0.02f)
        var last: FiveMoveSession.Decision = FiveMoveSession.Decision.Hold("none")
        diffs.forEachIndexed { index, diff ->
            last = session.onSettle(
                pass(
                    nowMs = 1_200L + index * 200L,
                    hash = 1_000L + index,
                    labelHash = 77L,
                    diffFraction = diff,
                ),
            )
        }
        assertThat(last).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.verifiedCount).isEqualTo(1)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(session.movesSnapshot().single().verification).contains("labels stable")
        assertThat(session.movesSnapshot().single().verification).contains("pixelDiff=")
    }

    @Test
    fun specialFlicker_doesNotChangeTheLabelKey() {
        val colors = latin()
        val plain = Board.fromColors(colors)
        val glowing = plain.setCopy(
            4,
            3,
            plain.get(4, 3).copy(special = com.match3vision.analyzer.vision.SpecialType.TWO_WAY_ARROW),
        )
        val dimmed = glowing.setCopy(4, 3, glowing.get(4, 3).copy(special = com.match3vision.analyzer.vision.SpecialType.NONE))
        assertThat(glowing.labelsAgree(dimmed)).isTrue()
        assertThat(plain.labelsAgree(glowing)).isTrue()
        var tracked = plain
        var key = plain.labelHash()
        listOf(glowing, dimmed, glowing).forEach { frame ->
            if (!tracked.labelsAgree(frame)) key = frame.labelHash()
            tracked = frame
        }
        assertThat(key).isEqualTo(plain.labelHash())
        val moved = plain.setCopy(0, 0, plain.get(0, 0).copy(color = TileColor.B, shape = com.match3vision.analyzer.vision.TileShape.STAR))
        assertThat(plain.labelsAgree(moved)).isFalse()
    }

    @Test
    fun followUpPenalty_isSmaller_andAMultiplierScalesGemsNotBlue() {
        val now = PlayMoveRanker.plyPoints(false, 0, 3, 0, true, followUp = false)
        val later = PlayMoveRanker.plyPoints(false, 0, 3, 0, true, followUp = true)
        assertThat(later - now).isEqualTo(
            PlayMoveRanker.UNCERTAIN_PENALTY - PlayMoveRanker.FOLLOW_UNCERTAIN_PENALTY,
        )
        val plain = PlayMoveRanker.plyPoints(false, 2, 2, 0, false, blueMultiplier = 1)
        val boosted = PlayMoveRanker.plyPoints(false, 2, 2, 0, false, blueMultiplier = 4)
        val blue = (2 * PlayMoveRanker.BLUE_POINTS)
        assertThat(plain - blue).isEqualTo(2 * PlayMoveRanker.GEM_POINTS)
        assertThat(boosted - blue).isEqualTo(2 * PlayMoveRanker.GEM_POINTS * 4)
        assertThat(PlayMoveRanker.SCORE_FORMULA).contains("+")
    }

    private fun latin(): Array<Array<TileColor>> {
        val palette = listOf(TileColor.R, TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O)
        return Array(7) { row -> Array(7) { col -> palette[(row + col) % 6] } }
    }

    private fun gates(selfCheck: Boolean, nowMs: Long = 1_000L) = FiveMoveSession.Gates(
        nowMs = nowMs,
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

    private fun gesture(beforeHash: Long) = FiveMoveSession.GestureFact(
        startedAtMs = 1_000L,
        nowMs = 1_100L,
        callbackCompleted = true,
        cancelled = false,
        cells = "(3,2)↔(3,3)",
        fromX = 1f,
        fromY = 1f,
        toX = 2f,
        toY = 1f,
        beforeHash = beforeHash,
        beforeUnknown = 0,
    )

    private fun pass(
        nowMs: Long,
        hash: Long,
        supported: Boolean = true,
        labelHash: Long = hash,
        diffFraction: Float = 0f,
    ) = FiveMoveSession.SettleSample(
        nowMs = nowMs,
        boardHash = hash,
        labelHash = labelHash,
        diffFraction = diffFraction,
        frameFresh = true,
        roiPlausible = true,
        visionPass = true,
        unknownCount = 0,
        ownUi = false,
        a11yConnected = true,
        swapSupported = supported,
    )
}
