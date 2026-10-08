package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FiveMoveSessionTest {

    @Test
    fun noMoveWithoutMeasuredSelfCheck() {
        val session = FiveMoveSession()
        assertThat(session.arm(0L)).isTrue()
        val decision = session.requestDispatch(gates(nowMs = 1_000L, selfCheckMeasured = false))
        assertThat(decision).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.gesturesDispatched).isEqualTo(0)
        assertThat(session.verifiedCount).isEqualTo(0)
        assertThat((decision as FiveMoveSession.Decision.Hold).reason).contains("MEASURED_WITHIN_TOLERANCE")
    }

    @Test
    fun failedMove1Verification_blocksMoves2Through5() {
        val session = FiveMoveSession()
        session.arm(0L)
        val go = session.requestDispatch(gates(nowMs = 1_000L))
        assertThat(go).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
        val permit = (go as FiveMoveSession.Decision.Go).permit
        assertThat(permit.moveNumber).isEqualTo(1)
        assertThat(session.consumePermit(permit)).isTrue()
        assertThat(session.consumePermit(permit)).isFalse()
        val noted = session.noteGesture(gesture(startedAtMs = 1_000L, nowMs = 1_200L, beforeHash = 11L))
        assertThat(noted).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        val failed = session.onSettle(
            sample(
                nowMs = 1_000L + FiveMoveSession.UNCHANGED_MIN_MS,
                boardHash = 11L,
                diffFraction = 0f,
            ),
        )
        assertThat(failed).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(session.verifiedCount).isEqualTo(0)
        assertThat(session.gesturesDispatched).isEqualTo(1)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.STOPPED)
        val blocked = session.requestDispatch(gates(nowMs = 4_000L))
        assertThat(blocked).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(session.gesturesDispatched).isEqualTo(1)
        assertThat(session.report()).contains("board unchanged")
        assertThat(session.report()).contains("measuredSessionMs=")
        assertThat(session.report()).contains("durationMs=")
    }

    @Test
    fun cancelledCallback_stopsAndDoesNotRetry() {
        val session = FiveMoveSession()
        session.arm(0L)
        val permit = (session.requestDispatch(gates(nowMs = 100L)) as FiveMoveSession.Decision.Go).permit
        assertThat(session.consumePermit(permit)).isTrue()
        val noted = session.noteGesture(
            gesture(startedAtMs = 100L, nowMs = 180L, beforeHash = 1L, completed = false, cancelled = true),
        )
        assertThat(noted).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(session.requestDispatch(gates(nowMs = 200L)))
            .isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(session.gesturesDispatched).isEqualTo(1)
        assertThat(session.verifiedCount).isEqualTo(0)
    }

    @Test
    fun fiveVerifiedMoves_thenStop_insideSixtySeconds() {
        val session = FiveMoveSession()
        session.arm(0L)
        repeat(FiveMoveSession.MAX_MOVES) { index ->
            val start = 1_000L + index * 2_000L
            val go = session.requestDispatch(gates(nowMs = start))
            assertThat(go).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
            val permit = (go as FiveMoveSession.Decision.Go).permit
            assertThat(permit.moveNumber).isEqualTo(index + 1)
            assertThat(session.consumePermit(permit)).isTrue()
            session.noteGesture(gesture(startedAtMs = start, nowMs = start + 100L, beforeHash = index.toLong()))
            val done = session.onSettle(
                sample(
                    nowMs = start + 800L,
                    boardHash = 100L + index,
                    diffFraction = 0f,
                    unknownCount = index,
                ),
            )
            if (index < FiveMoveSession.MAX_MOVES - 1) {
                assertThat(done).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
                assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
            } else {
                assertThat(done).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
            }
        }
        assertThat(session.verifiedCount).isEqualTo(5)
        assertThat(session.gesturesDispatched).isEqualTo(5)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.STOPPED)
        assertThat(session.stoppedAtMs - session.startedAtMs).isLessThan(FiveMoveSession.SESSION_LIMIT_MS)
        assertThat(session.requestDispatch(gates(nowMs = session.stoppedAtMs + 10L)))
            .isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(session.gesturesDispatched).isEqualTo(5)
        val report = session.report()
        assertThat(report).contains("sessionLimitMs=60000")
        assertThat(report).contains("perMoveBudgetMs=12000")
        assertThat(report).contains("measuredSessionMs=")
        assertThat(session.movesSnapshot()).hasSize(5)
        assertThat(session.movesSnapshot().map { it.durationMs }).containsExactly(800L, 800L, 800L, 800L, 800L)
    }

    @Test
    fun sessionHardLimit_isSixtySeconds_notSixtyPerMove() {
        val session = FiveMoveSession()
        session.arm(0L)
        val decision = session.requestDispatch(gates(nowMs = FiveMoveSession.SESSION_LIMIT_MS))
        assertThat(decision).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat((decision as FiveMoveSession.Decision.Stop).reason).contains("60s")
        assertThat(session.gesturesDispatched).isEqualTo(0)
    }

    @Test
    fun perMoveBudget_stopsTheSession() {
        val session = FiveMoveSession()
        session.arm(0L)
        val permit = (session.requestDispatch(gates(nowMs = 10L)) as FiveMoveSession.Decision.Go).permit
        session.consumePermit(permit)
        session.noteGesture(gesture(startedAtMs = 10L, nowMs = 20L, beforeHash = 4L))
        val decision = session.onSettle(
            sample(
                nowMs = 10L + FiveMoveSession.PER_MOVE_BUDGET_MS,
                boardHash = 9L,
                diffFraction = 0f,
            ),
        )
        assertThat(decision).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat((decision as FiveMoveSession.Decision.Stop).reason).contains("per-move")
        assertThat(session.verifiedCount).isEqualTo(0)
        assertThat(session.requestDispatch(gates(nowMs = 13_000L)))
            .isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
    }

    @Test
    fun abortPaths_stopImmediately() {
        val ownUi = FiveMoveSession().also { it.arm(0L) }
        assertThat(ownUi.requestDispatch(gates(nowMs = 10L, ownUi = true)))
            .isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(ownUi.gesturesDispatched).isEqualTo(0)

        val a11y = FiveMoveSession().also { it.arm(0L) }
        assertThat(a11y.requestDispatch(gates(nowMs = 10L, a11yConnected = false)))
            .isInstanceOf(FiveMoveSession.Decision.Stop::class.java)

        val roi = FiveMoveSession().also { it.arm(0L) }
        assertThat(roi.requestDispatch(gates(nowMs = 10L, roiPlausible = false)))
            .isInstanceOf(FiveMoveSession.Decision.Stop::class.java)

        val settling = FiveMoveSession().also { it.arm(0L) }
        val permit = (settling.requestDispatch(gates(nowMs = 10L)) as FiveMoveSession.Decision.Go).permit
        settling.consumePermit(permit)
        settling.noteGesture(gesture(startedAtMs = 10L, nowMs = 20L, beforeHash = 1L))
        assertThat(
            settling.onSettle(sample(nowMs = 100L, boardHash = 2L, diffFraction = 0f, roiPlausible = false)),
        ).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(settling.verifiedCount).isEqualTo(0)

        val stopped = FiveMoveSession().also { it.arm(0L) }
        assertThat(stopped.abort("STOP pressed", 50L))
            .isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(stopped.requestDispatch(gates(nowMs = 60L)))
            .isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
    }

    @Test
    fun freshPassAfterARealChange_countsTheProbe() {
        val session = FiveMoveSession()
        session.arm(0L)
        val permit = (session.requestDispatch(gates(nowMs = 2_100L)) as FiveMoveSession.Decision.Go).permit
        session.consumePermit(permit)
        session.noteGesture(gesture(startedAtMs = 2_100L, nowMs = 2_300L, beforeHash = 7L))
        assertThat(session.onSettle(sample(nowMs = 2_400L, boardHash = 7L, diffFraction = null)))
            .isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.onSettle(sample(nowMs = 2_500L, boardHash = 8L, diffFraction = 0.5f)))
            .isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        val pass = session.onSettle(
            sample(nowMs = 2_700L, boardHash = 8L, diffFraction = 0.01f, unknownCount = 1),
        )
        assertThat(pass).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.verifiedCount).isEqualTo(1)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        val move = session.movesSnapshot().single()
        assertThat(move.verification).contains("vision PASS")
        assertThat(move.callback).isEqualTo("onCompleted")
        assertThat(move.beforeUnknown).isEqualTo(0)
        assertThat(move.afterUnknown).isEqualTo(1)
        assertThat(move.durationMs).isEqualTo(600L)
        assertThat(move.fromX).isEqualTo(10f)
        assertThat(move.toX).isEqualTo(40f)
    }

    private fun gates(
        nowMs: Long,
        selfCheckMeasured: Boolean = true,
        a11yConnected: Boolean = true,
        ownUi: Boolean = false,
        roiPlausible: Boolean = true,
        visionPass: Boolean = true,
        frameFresh: Boolean = true,
        overlayCollapsed: Boolean = true,
        overlayOutsideRoi: Boolean = true,
        msSinceCollapse: Long = FiveMoveSession.MIN_POST_COLLAPSE_MS,
    ) = FiveMoveSession.Gates(
        nowMs = nowMs,
        a11yConnected = a11yConnected,
        selfCheckMeasured = selfCheckMeasured,
        overlayCollapsed = overlayCollapsed,
        overlayOutsideRoi = overlayOutsideRoi,
        visionPass = visionPass,
        frameFresh = frameFresh,
        ownUi = ownUi,
        msSinceCollapse = msSinceCollapse,
        roiPlausible = roiPlausible,
    )

    private fun gesture(
        startedAtMs: Long,
        nowMs: Long,
        beforeHash: Long,
        completed: Boolean = true,
        cancelled: Boolean = false,
    ) = FiveMoveSession.GestureFact(
        startedAtMs = startedAtMs,
        nowMs = nowMs,
        callbackCompleted = completed,
        cancelled = cancelled,
        cells = "(0,0)↔(0,1)",
        fromX = 10f,
        fromY = 20f,
        toX = 40f,
        toY = 20f,
        beforeHash = beforeHash,
        beforeUnknown = 0,
    )

    private fun sample(
        nowMs: Long,
        boardHash: Long,
        diffFraction: Float?,
        unknownCount: Int = 0,
        roiPlausible: Boolean = true,
        visionPass: Boolean = true,
        frameFresh: Boolean = true,
    ) = FiveMoveSession.SettleSample(
        nowMs = nowMs,
        boardHash = boardHash,
        diffFraction = diffFraction,
        frameFresh = frameFresh,
        roiPlausible = roiPlausible,
        visionPass = visionPass,
        unknownCount = unknownCount,
        ownUi = false,
        a11yConnected = true,
    )
}
