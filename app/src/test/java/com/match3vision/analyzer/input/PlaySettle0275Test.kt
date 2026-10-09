package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaySettle0275Test {
    @Test
    fun boosterTap_unchangedCircles_swipesInsideThePlayableWindow() {
        val session = FiveMoveSession()
        session.arm(0L)
        val tapAt = 1_000L
        session.armBoosterSettle(
            x = 220f,
            y = 924f,
            nowMs = tapAt,
            playExport = "booster",
            swipeSequence = 1L,
            beforeHash = 7L,
            beforeLabel = 7L,
            beforeCircles = 10,
        )
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.SETTLING)
        session.onSettle(pass(nowMs = tapAt + 400L, hash = 7L, sequence = 2L, circles = 10))
        val playable = session.onSettle(pass(nowMs = tapAt + 800L, hash = 7L, sequence = 3L, circles = 10))
        assertThat(playable).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(session.stopReason).isEmpty()
        assertThat(session.swipesVerified).isEqualTo(0)
        val move = session.movesSnapshot().single()
        assertThat(move.verification).contains("booster playable")
        assertThat(move.verification).doesNotContain("board unchanged")
        val swipeAt = tapAt + 900L
        assertThat(swipeAt - tapAt).isLessThan(FiveMoveSession.BOOSTER_PLAYABLE_MS)
        val go = session.requestDispatch(gates(swipeAt, frameSequence = 4L))
        assertThat(go).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
        assertThat((go as FiveMoveSession.Decision.Go).permit.moveNumber).isEqualTo(2)
    }

    @Test
    fun noPlayableBoardWithinFifteenSeconds_logsAndTheNextPassFrameSwipes() {
        val session = FiveMoveSession()
        session.arm(0L)
        session.armBoosterSettle(
            x = 220f,
            y = 924f,
            nowMs = 0L,
            playExport = "booster",
            swipeSequence = 1L,
            beforeHash = 7L,
            beforeLabel = 7L,
            beforeCircles = 10,
        )
        val waiting = session.onSettle(pass(nowMs = 1_000L, hash = 7L, sequence = 2L, visionPass = false, unknown = 4))
        assertThat(waiting).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.SETTLING)
        val gaveUp = session.onSettle(
            pass(nowMs = FiveMoveSession.BOOSTER_PLAYABLE_MS, hash = 8L, sequence = 3L, visionPass = false, unknown = 4),
        )
        assertThat(gaveUp).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat((gaveUp as FiveMoveSession.Decision.Hold).reason).contains("no playable board")
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(session.stopReason).isEmpty()
        assertThat(session.report()).contains("no playable board within 15000ms")
        val go = session.requestDispatch(gates(FiveMoveSession.BOOSTER_PLAYABLE_MS + 100L, frameSequence = 4L))
        assertThat(go).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
    }

    @Test
    fun strandedSettle_afterThePlayableWindow_stillSwipes() {
        val session = FiveMoveSession()
        session.arm(0L)
        session.armBoosterSettle(
            x = 220f,
            y = 924f,
            nowMs = 0L,
            playExport = "booster",
            swipeSequence = 1L,
            beforeHash = 7L,
            beforeLabel = 7L,
            beforeCircles = 10,
        )
        val early = session.requestDispatch(gates(1_000L, frameSequence = 4L))
        assertThat(early).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        val go = session.requestDispatch(gates(FiveMoveSession.BOOSTER_PLAYABLE_MS, frameSequence = 5L))
        assertThat(go).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(session.stopReason).isEmpty()
    }

    @Test
    fun passBoardsIdlePastTwentySeconds_logsTheBlockEveryFiveSeconds() {
        val session = FiveMoveSession()
        session.arm(0L)
        session.beginOwnGesture(0L)
        val times = longArrayOf(21_000L, 26_000L, 31_000L)
        times.forEach { now ->
            val held = session.requestDispatch(gates(now, frameSequence = now, fresh = false))
            assertThat(held).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        }
        val reasons = session.report().lines().filter { it.startsWith("idle reason=") }
        assertThat(reasons).hasSize(3)
        assertThat(reasons).contains("idle reason=HOLD — frame is not fresh")
    }

    @Test
    fun idleHelpTouchesDoNotCount_andTwoRecentTouchesStillStop() {
        val stuck = FiveMoveSession()
        stuck.arm(0L)
        stuck.beginOwnGesture(1_000L)
        stuck.finishOwnGesture(1_000L)
        assertThat(stuck.noteOutsideTouch(1_000L + 21_000L, 10f, 10f)).isNull()
        assertThat(stuck.noteOutsideTouch(1_000L + 22_000L, 12f, 12f)).isNull()
        assertThat(stuck.outsideTouches).isEqualTo(0)
        assertThat(stuck.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(stuck.report()).contains("result=ignored-idle-help")
        assertThat(stuck.report()).doesNotContain("result=stop")

        val live = FiveMoveSession()
        live.arm(0L)
        live.beginOwnGesture(5_000L)
        live.finishOwnGesture(5_000L)
        assertThat(live.noteOutsideTouch(7_000L, 10f, 10f)).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        val stopped = live.noteOutsideTouch(8_000L, 11f, 11f)
        assertThat(stopped).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(live.stopReason).contains("user interference")
    }

    private fun gates(
        nowMs: Long,
        frameSequence: Long = 4L,
        fresh: Boolean = true,
    ) = FiveMoveSession.Gates(
        nowMs = nowMs,
        a11yConnected = true,
        selfCheckMeasured = true,
        overlayCollapsed = true,
        overlayOutsideRoi = true,
        visionPass = true,
        frameFresh = fresh,
        ownUi = false,
        msSinceCollapse = FiveMoveSession.MIN_POST_COLLAPSE_MS,
        roiPlausible = true,
        frameSequence = frameSequence,
    )

    private fun pass(
        nowMs: Long,
        hash: Long,
        sequence: Long,
        circles: Int? = null,
        visionPass: Boolean = true,
        unknown: Int = 0,
    ) = FiveMoveSession.SettleSample(
        nowMs = nowMs,
        boardHash = hash,
        diffFraction = 0.02f,
        frameFresh = true,
        roiPlausible = true,
        visionPass = visionPass,
        unknownCount = unknown,
        ownUi = false,
        a11yConnected = true,
        frameSequence = sequence,
        labelHash = hash,
        circlesBright = circles,
        circlesClassifiable = circles != null,
    )
}
