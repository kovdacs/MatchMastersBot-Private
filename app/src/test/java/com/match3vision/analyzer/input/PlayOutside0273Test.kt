package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.vision.SpecialType
import com.match3vision.analyzer.vision.TileColor
import com.match3vision.analyzer.vision.TileShape
import org.junit.Test

class PlayOutside0273Test {
    @Test
    fun outsideTouch_isLoggedWithTimeCoordinatesTypeAndSource() {
        val session = FiveMoveSession()
        session.arm(0L)
        session.beginOwnGesture(1_000L)
        assertThat(session.noteOutsideTouch(1_250L, 12f, 34f, "ACTION_OUTSIDE", "bubble")).isNull()
        val report = session.report()
        assertThat(report).contains("tSinceGestureMs=250")
        assertThat(report).contains("x=12.0")
        assertThat(report).contains("y=34.0")
        assertThat(report).contains("event=ACTION_OUTSIDE")
        assertThat(report).contains("source=bubble")
        assertThat(report).contains("result=ignored-own-gesture")
        assertThat(session.outsideTouches).isEqualTo(0)
    }

    @Test
    fun boosterWindow_ignoresOutsideTouchesUntilPlayablePlusSlack() {
        val session = FiveMoveSession()
        session.arm(0L)
        session.beginBoosterWindow(1_000L)
        session.armBoosterSettle(
            x = 220f,
            y = 924f,
            nowMs = 1_000L,
            playExport = "booster",
            swipeSequence = 1L,
            beforeHash = 4L,
            beforeLabel = 4L,
            beforeCircles = 8,
        )
        assertThat(session.noteOutsideTouch(5_000L, 900f, 2_000f, "ACTION_OUTSIDE", "bubble")).isNull()
        session.onSettle(pass(nowMs = 5_000L, hash = 9L, sequence = 2L))
        val playable = session.onSettle(pass(nowMs = 5_400L, hash = 9L, sequence = 3L))
        assertThat(playable).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(session.noteOutsideTouch(6_000L, 900f, 2_000f, "ACTION_OUTSIDE", "bubble")).isNull()
        assertThat(session.outsideTouches).isEqualTo(0)
        assertThat(session.report()).contains("result=ignored-booster-window")
        val after = session.noteOutsideTouch(5_400L + FiveMoveSession.OWN_GESTURE_AFTER_MS, 900f, 2_000f)
        assertThat(after).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.outsideTouches).isEqualTo(1)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
    }

    @Test
    fun boosterWindow_unknownHudAndMissingCirclesDoNotStop() {
        val session = FiveMoveSession()
        session.arm(0L)
        session.beginBoosterWindow(1_000L)
        repeat(4) {
            val hud = session.notePlayHud(PlayGate.MENU, 1_000L + it)
            assertThat(hud).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        }
        session.noteDispatchedHud(PlayGate.UNKNOWN, 2_000L)
        session.noteDispatchedHud(PlayGate.UNKNOWN, 2_100L)
        assertThat(session.notePlayHud(PlayGate.UNKNOWN, 2_200L))
            .isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.noteCircles(true, 0, true, 3_000L, 1L)).isNull()
        assertThat(session.noteCircles(true, 0, true, 3_100L, 2L)).isNull()
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(session.stopReason).isEmpty()
    }

    @Test
    fun oneOutsideTouch_pausesAndTwoWithinTenSecondsStop() {
        val session = FiveMoveSession()
        session.arm(0L)
        val paused = session.noteOutsideTouch(1_000L, 10f, 10f, "ACTION_OUTSIDE", "bubble")
        assertThat(paused).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.requestDispatch(gates(2_000L))).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        val resumed = session.requestDispatch(gates(1_000L + FiveMoveSession.OUTSIDE_PAUSE_MS))
        assertThat(resumed).isInstanceOf(FiveMoveSession.Decision.Go::class.java)

        val burst = FiveMoveSession()
        burst.arm(0L)
        assertThat(burst.noteOutsideTouch(1_000L, 10f, 10f)).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        val stopped = burst.noteOutsideTouch(2_000L, 11f, 11f)
        assertThat(stopped).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(burst.stopReason).contains("user interference")

        val spaced = FiveMoveSession()
        spaced.arm(0L)
        assertThat(spaced.noteOutsideTouch(1_000L, 10f, 10f)).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        val again = spaced.noteOutsideTouch(1_000L + FiveMoveSession.OUTSIDE_CLUSTER_MS + 1L, 12f, 12f)
        assertThat(again).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(spaced.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
    }

    @Test
    fun animatingSpecial_duringBoosterWindow_keepsPlaying() {
        val session = FiveMoveSession()
        session.arm(0L)
        session.beginBoosterWindow(1_000L)
        val spinning = animatedSpecials(SpecialType.LIGHTNING, SpecialType.BOMB, TileColor.B)
        val later = animatedSpecials(SpecialType.BOMB, SpecialType.TWO_WAY_ARROW, TileColor.Y)
        assertThat(spinning.unknownCount()).isEqualTo(0)
        assertThat(later.unknownCount()).isEqualTo(0)
        assertThat(spinning.get(2, 2).isUnknown).isFalse()
        assertThat(later.get(4, 5).isUnknown).isFalse()
        assertThat(Board.labelsWithinOne(spinning.labelKeys(), later.labelKeys())).isTrue()
        session.armBoosterSettle(
            x = 220f,
            y = 924f,
            nowMs = 1_000L,
            playExport = "booster",
            swipeSequence = 1L,
            beforeHash = 1L,
            beforeLabel = 1L,
            beforeCircles = 8,
        )
        assertThat(
            session.noteOutsideTouch(
                2_000L, 40f, 40f, "TYPE_WINDOW_CONTENT_CHANGED", "accessibility", "touchscreen", "finger",
            ),
        ).isNull()
        assertThat(
            session.noteOutsideTouch(2_100L, 40f, 40f, "TYPE_WINDOWS_CHANGED", "accessibility"),
        ).isNull()
        assertThat(
            session.noteOutsideTouch(2_200L, 40f, 40f, "BOARD_CHANGE", "vision"),
        ).isNull()
        assertThat(session.outsideTouches).isEqualTo(0)
        session.onSettle(
            pass(
                nowMs = 5_000L,
                hash = spinning.labelHash(),
                sequence = 2L,
                keys = spinning.labelKeys(),
            ),
        )
        val playable = session.onSettle(
            pass(
                nowMs = 5_400L,
                hash = later.labelHash(),
                sequence = 3L,
                keys = later.labelKeys(),
            ),
        )
        assertThat(playable).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(session.stopReason).isEmpty()
        assertThat(session.outsideTouches).isEqualTo(0)
        assertThat(session.movesSnapshot().last().userInterference).isFalse()
        assertThat(session.report()).contains("result=ignored-not-motion")
        assertThat(session.report()).doesNotContain("result=pause")
        assertThat(session.report()).doesNotContain("result=stop")
        val next = session.requestDispatch(gates(7_000L))
        assertThat(next).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
    }

    private fun animatedSpecials(first: SpecialType, second: SpecialType, color: TileColor): Board {
        val board = Board.fromColors(Array(7) { Array(7) { TileColor.R } })
        return board
            .setCopy(
                2, 2,
                board.get(2, 2).copy(color = color, shape = TileShape.UNKNOWN, special = first),
            )
            .setCopy(
                4, 5,
                board.get(4, 5).copy(
                    color = TileColor.UNKNOWN,
                    shape = TileShape.UNKNOWN,
                    special = second,
                ),
            )
    }

    private fun gates(nowMs: Long) = FiveMoveSession.Gates(
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
    )

    private fun pass(
        nowMs: Long,
        hash: Long,
        sequence: Long,
        keys: LongArray? = null,
    ) = FiveMoveSession.SettleSample(
        nowMs = nowMs,
        boardHash = hash,
        diffFraction = 0.2f,
        frameFresh = true,
        roiPlausible = true,
        visionPass = true,
        unknownCount = 0,
        ownUi = false,
        a11yConnected = true,
        frameSequence = sequence,
        labelHash = hash,
        labelKeys = keys,
    )
}
