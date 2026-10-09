package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayOutside0272Test {
    @Test
    fun boosterTap_isNotUserInterference() {
        val session = FiveMoveSession()
        session.arm(0L)
        val x = 220f
        val y = 924f
        session.beginOwnGesture(x, y, x, y)
        assertThat(session.noteOutsideTouch(40L, x, y)).isNull()
        session.finishOwnGesture(100L)
        assertThat(session.noteOutsideTouch(1_400L, 0f, 0f)).isNull()
        session.armBoosterSettle(
            x = x,
            y = y,
            nowMs = 100L,
            playExport = "booster",
            swipeSequence = 1L,
            beforeHash = 4L,
            beforeLabel = 4L,
            beforeCircles = 8,
        )
        val late = session.noteOutsideTouch(
            100L + FiveMoveSession.OWN_GESTURE_AFTER_MS,
            x + 50f,
            y,
        )
        assertThat(late).isNull()
        assertThat(session.outsideTouches).isEqualTo(0)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.SETTLING)
        assertThat(session.stopReason).isEmpty()
        assertThat(session.report()).contains("stop=none")
        assertThat(session.report()).contains("outsideTouches=0")
        assertThat(session.requestDispatch(gates(2_000L))).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
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
}
