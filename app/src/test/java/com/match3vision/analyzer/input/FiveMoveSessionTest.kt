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
        assertThat(playable(session, start = 1_000L, hash = 11L, seq = 2L))
            .isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        val retry = session.onSettle(
            sample(nowMs = 1_000L + 3_000L + FiveMoveSession.LANDED_WINDOW_MS, boardHash = 11L, diffFraction = 0f, frameSequence = 4L),
        )
        assertThat(retry).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(session.playSkip).isEqualTo(1)
        val again = session.requestDispatch(gates(nowMs = 12_000L))
        assertThat(again).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
        session.consumePermit((again as FiveMoveSession.Decision.Go).permit)
        session.noteGesture(gesture(startedAtMs = 12_000L, nowMs = 12_100L, beforeHash = 11L))
        playable(session, start = 12_000L, hash = 11L, seq = 6L)
        val failed = session.onSettle(
            sample(
                nowMs = 12_000L + 3_000L + FiveMoveSession.LANDED_WINDOW_MS,
                boardHash = 11L,
                diffFraction = 0f,
                frameSequence = 8L,
            ),
        )
        assertThat(failed).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.verifiedCount).isEqualTo(0)
        assertThat(session.gesturesDispatched).isEqualTo(2)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(session.stopReason).isEmpty()
        val blocked = session.requestDispatch(gates(nowMs = 30_000L, frameSequence = 9L))
        assertThat(blocked).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
        assertThat(session.gesturesDispatched).isEqualTo(2)
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
        assertThat(noted).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(session.requestDispatch(gates(nowMs = 200L, frameSequence = 2L)))
            .isInstanceOf(FiveMoveSession.Decision.Go::class.java)
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
            val done = playable(session, start = start, hash = 100L + index, seq = index * 2L + 2L)
            if (index < FiveMoveSession.MAX_MOVES - 1) {
                assertThat(done).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
                assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
            } else {
                assertThat(done).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
            }
        }
        assertThat(session.verifiedCount).isEqualTo(FiveMoveSession.MAX_MOVES)
        assertThat(session.gesturesDispatched).isEqualTo(FiveMoveSession.MAX_MOVES)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.STOPPED)
        assertThat(session.stoppedAtMs - session.startedAtMs).isLessThan(FiveMoveSession.SESSION_LIMIT_MS)
        assertThat(session.requestDispatch(gates(nowMs = session.stoppedAtMs + 10L)))
            .isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(session.gesturesDispatched).isEqualTo(FiveMoveSession.MAX_MOVES)
        val report = session.report()
        assertThat(report).contains("sessionLimitMs=600000")
        assertThat(report).contains("settleWaitMs=60000")
        assertThat(report).contains("measuredSessionMs=")
        assertThat(session.movesSnapshot()).hasSize(FiveMoveSession.MAX_MOVES)
        assertThat(session.movesSnapshot().map { it.durationMs })
            .containsExactlyElementsIn(List(FiveMoveSession.MAX_MOVES) { 3_000L })
    }

    @Test
    fun sessionHardLimit_isSixtySeconds_notSixtyPerMove() {
        val session = FiveMoveSession()
        session.arm(0L)
        val decision = session.requestDispatch(gates(nowMs = FiveMoveSession.SESSION_LIMIT_MS))
        assertThat(decision).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat((decision as FiveMoveSession.Decision.Stop).reason).contains("600s")
        assertThat(session.gesturesDispatched).isEqualTo(0)
    }

    @Test
    fun movesRemaining_stopsOnlyWhenTheCircleRowReadsZero() {
        val running = FiveMoveSession()
        running.arm(0L)
        assertThat(running.noteCircles(false, null, true, 10L, 1L)).isNull()
        assertThat(running.noteCircles(true, 3, true, 10L, 2L)).isNull()
        assertThat(running.noteCircles(true, 0, true, 20L, 3L)).isNull()
        assertThat(running.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        val stopped = running.noteCircles(true, 0, true, 30L, 4L) as FiveMoveSession.Decision.Stop
        assertThat(stopped.reason).contains("moves spent")
        assertThat(running.phase).isEqualTo(FiveMoveSession.Phase.STOPPED)
    }

    @Test
    fun perMoveBudget_stopsTheSession() {
        val session = FiveMoveSession()
        session.arm(0L)
        val permit = (session.requestDispatch(gates(nowMs = 10L)) as FiveMoveSession.Decision.Go).permit
        session.consumePermit(permit)
        session.noteGesture(gesture(startedAtMs = 10L, nowMs = 20L, beforeHash = 4L))
        val early = session.onSettle(
            sample(
                nowMs = 10L + FiveMoveSession.PER_MOVE_BUDGET_MS,
                boardHash = 9L,
                diffFraction = 0.45f,
                frameSequence = 2L,
            ),
        )
        assertThat(early).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.SETTLING)
        val decision = session.onSettle(
            sample(
                nowMs = 10L + FiveMoveSession.SETTLE_WAIT_MS,
                boardHash = 9L,
                diffFraction = 0.45f,
                frameSequence = 3L,
            ),
        )
        assertThat(decision).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(session.stopReason).isEmpty()
        assertThat(session.verifiedCount).isEqualTo(1)
        assertThat(session.requestDispatch(gates(nowMs = 60_100L, frameSequence = 4L)))
            .isInstanceOf(FiveMoveSession.Decision.Go::class.java)
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
            .isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(roi.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(roi.gesturesDispatched).isEqualTo(0)
        assertThat(roi.requestDispatch(gates(nowMs = 20L)))
            .isInstanceOf(FiveMoveSession.Decision.Go::class.java)

        val settling = FiveMoveSession().also { it.arm(0L) }
        val permit = (settling.requestDispatch(gates(nowMs = 10L)) as FiveMoveSession.Decision.Go).permit
        settling.consumePermit(permit)
        settling.noteGesture(gesture(startedAtMs = 10L, nowMs = 20L, beforeHash = 1L))
        assertThat(
            settling.onSettle(sample(nowMs = 100L, boardHash = 2L, diffFraction = 0f, roiPlausible = false)),
        ).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(settling.phase).isEqualTo(FiveMoveSession.Phase.SETTLING)
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
        val pass = playable(session, start = 2_100L, hash = 8L, seq = 4L, unknownCount = 1)
        assertThat(pass).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.verifiedCount).isEqualTo(1)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        val move = session.movesSnapshot().single()
        assertThat(move.verification).contains("vision PASS")
        assertThat(move.callback).isEqualTo("onCompleted")
        assertThat(move.beforeUnknown).isEqualTo(0)
        assertThat(move.afterUnknown).isEqualTo(1)
        assertThat(move.durationMs).isEqualTo(3_000L)
        assertThat(move.fromX).isEqualTo(10f)
        assertThat(move.toX).isEqualTo(40f)
    }

    @Test
    fun animationFrames_thenStableChangedPass_verifiesAndAllowsTheNextMove() {
        val session = FiveMoveSession()
        session.arm(0L)
        val first = (session.requestDispatch(gates(nowMs = 1_000L, frameSequence = 10L))
            as FiveMoveSession.Decision.Go).permit
        session.consumePermit(first)
        session.noteGesture(gesture(startedAtMs = 1_000L, nowMs = 1_200L, beforeHash = 11L))
        assertThat(
            session.onSettle(
                sample(
                    nowMs = 2_065L,
                    boardHash = 11L,
                    diffFraction = 0.4f,
                    unknownCount = 31,
                    roiPlausible = false,
                    visionPass = false,
                    frameSequence = 11L,
                ),
            ),
        ).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(
            session.onSettle(
                sample(
                    nowMs = 2_400L,
                    boardHash = 11L,
                    diffFraction = null,
                    unknownCount = 8,
                    visionPass = false,
                    frameSequence = 12L,
                ),
            ),
        ).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        val verified = playable(session, start = 1_000L, hash = 99L, seq = 20L)
        assertThat(verified).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.verifiedCount).isEqualTo(1)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        val move = session.movesSnapshot().single()
        assertThat(move.ignoredTransient).isAtLeast(2)
        assertThat(move.ignoredReasons).contains("implausible ROI")
        assertThat(move.ignoredReasons).contains("vision HOLD unk=8")
        assertThat(session.requestDispatch(gates(nowMs = 4_100L, frameSequence = 21L)))
            .isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        val second = session.requestDispatch(gates(nowMs = 4_200L, frameSequence = 22L))
        assertThat(second).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
        assertThat((second as FiveMoveSession.Decision.Go).permit.moveNumber).isEqualTo(2)
    }

    @Test
    fun flaggedUserTouch_boardChangeIsNotAutomaticSuccess() {
        val session = FiveMoveSession()
        session.arm(0L)
        val permit = (session.requestDispatch(gates(nowMs = 1_000L)) as FiveMoveSession.Decision.Go).permit
        session.consumePermit(permit)
        session.noteGesture(gesture(startedAtMs = 1_000L, nowMs = 1_200L, beforeHash = 11L))
        session.onSettle(
            sample(
                nowMs = 2_065L,
                boardHash = 11L,
                diffFraction = 0.8f,
                unknownCount = 31,
                roiPlausible = false,
                visionPass = false,
            ),
        )
        assertThat(session.noteOutsideTouch(2_200L)).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        val flagged = session.noteOutsideTouch(2_300L)
        assertThat(flagged).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        val manual = session.onSettle(
            sample(
                nowMs = 3_000L,
                boardHash = 77L,
                diffFraction = 0.01f,
                unknownCount = 0,
            ),
        )
        assertThat(manual).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(session.verifiedCount).isEqualTo(0)
        assertThat(session.gesturesDispatched).isEqualTo(1)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.STOPPED)
        val report = session.report()
        assertThat(report).contains("user interference")
        assertThat(report).contains("cannot tell our gesture from a finger on the glass")
        assertThat(report).doesNotContain("PASS — callback completed")
        assertThat(session.movesSnapshot().single().userInterference).isTrue()
        assertThat(session.requestDispatch(gates(nowMs = 3_100L)))
            .isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(session.gesturesDispatched).isEqualTo(1)
    }

    @Test
    fun ownSwipeWindow_doesNotFlagUserInterference() {
        val session = FiveMoveSession()
        session.arm(0L)
        val permit = (session.requestDispatch(gates(nowMs = 100L)) as FiveMoveSession.Decision.Go).permit
        session.consumePermit(permit)
        session.noteGesture(gesture(startedAtMs = 100L, nowMs = 200L, beforeHash = 1L))
        session.suppressOutsideTouchUntil(1_000L)
        assertThat(session.noteOutsideTouch(500L)).isNull()
        assertThat(session.outsideTouches).isEqualTo(0)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.SETTLING)
    }

    @Test
    fun cascadePastTheSettleBudget_isChangedUnsettled_andStops() {
        val session = FiveMoveSession()
        session.arm(0L)
        val permit = (session.requestDispatch(gates(nowMs = 1_000L)) as FiveMoveSession.Decision.Go).permit
        session.consumePermit(permit)
        session.noteGesture(gesture(startedAtMs = 1_000L, nowMs = 1_200L, beforeHash = 11L))
        val changing = listOf(0.16f to 2, 0.42f to 2, 0.98f to 24)
        changing.forEachIndexed { index, (diff, unk) ->
            val decision = session.onSettle(
                sample(
                    nowMs = 1_000L + 1_000L * (index + 1),
                    boardHash = 11L,
                    diffFraction = diff,
                    unknownCount = unk,
                    visionPass = false,
                    roiPlausible = true,
                ),
            )
            assertThat(decision).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
            assertThat(session.requestDispatch(gates(nowMs = 1_000L + 1_000L * (index + 1) + 10L)))
                .isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        }
        assertThat(session.gesturesDispatched).isEqualTo(1)
        assertThat(session.verifiedCount).isEqualTo(0)
        val stillPlaying = session.onSettle(
            sample(
                nowMs = 1_000L + 25_000L,
                boardHash = 80L,
                diffFraction = 0.55f,
                unknownCount = 24,
                visionPass = false,
                frameSequence = 8L,
                frameAgeMs = 4_900L,
            ),
        )
        assertThat(stillPlaying).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.SETTLING)
        val expired = session.onSettle(
            sample(
                nowMs = 1_000L + FiveMoveSession.SETTLE_WAIT_MS,
                boardHash = 80L,
                diffFraction = 0.55f,
                unknownCount = 24,
                visionPass = false,
                frameSequence = 9L,
            ),
        )
        assertThat(expired).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(session.verifiedCount).isEqualTo(0)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.STOPPED)
        val move = session.movesSnapshot().single()
        assertThat(move.verification).contains("settle wait")
        assertThat(move.verification).doesNotContain("vision PASS")
        assertThat(move.boardKeptChanging).isTrue()
        assertThat(move.durationMs).isEqualTo(FiveMoveSession.SETTLE_WAIT_MS)
        assertThat(move.ignoredReasons).contains("vision HOLD unk=2")
        assertThat(move.ignoredReasons).contains("vision HOLD unk=24")
        val report = session.report()
        assertThat(report).contains("settle wait")
        assertThat(report).contains("ignoredReasons=")
        assertThat(report).contains("settleMs=")
        assertThat(report).contains("settleWaitMs=60000")
        assertThat(report).contains("not a verified move")
        assertThat(session.requestDispatch(gates(nowMs = 30_000L)))
            .isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(session.gesturesDispatched).isEqualTo(1)
    }

    @Test
    fun changingBoard_thenStablePassInsideTheBudget_countsTheMove() {
        val session = FiveMoveSession()
        session.arm(0L)
        val permit = (session.requestDispatch(gates(nowMs = 0L)) as FiveMoveSession.Decision.Go).permit
        session.consumePermit(permit)
        session.noteGesture(gesture(startedAtMs = 0L, nowMs = 100L, beforeHash = 3L))
        assertThat(
            session.onSettle(
                sample(nowMs = 1_000L, boardHash = 3L, diffFraction = 0.16f, unknownCount = 2, visionPass = false),
            ),
        ).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        session.onSettle(
            sample(nowMs = 3_800L, boardHash = 9L, diffFraction = 0.45f, unknownCount = 0, frameSequence = 3L),
        )
        val verified = session.onSettle(
            sample(nowMs = 4_202L, boardHash = 9L, diffFraction = 0.02f, unknownCount = 0, frameSequence = 4L),
        )
        assertThat(verified).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.verifiedCount).isEqualTo(1)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        val move = session.movesSnapshot().single()
        assertThat(move.verification).contains("vision PASS")
        assertThat(move.verification).doesNotContain("CHANGED_UNSETTLED")
        assertThat(move.durationMs).isEqualTo(4_202L)
        assertThat(move.ignoredReasons).contains("vision HOLD unk=2")
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
        frameSequence: Long = 0L,
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
        frameSequence = frameSequence,
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
        frameSequence: Long = 0L,
        frameAgeMs: Long = 0L,
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
        frameSequence = frameSequence,
        frameAgeMs = frameAgeMs,
    )

    private fun playable(
        session: FiveMoveSession,
        start: Long,
        hash: Long,
        seq: Long,
        unknownCount: Int = 0,
    ): FiveMoveSession.Decision {
        session.onSettle(
            sample(
                nowMs = start + 2_600L,
                boardHash = hash,
                diffFraction = 0.45f,
                unknownCount = unknownCount,
                frameSequence = seq,
            ),
        )
        return session.onSettle(
            sample(
                nowMs = start + 3_000L,
                boardHash = hash,
                diffFraction = 0.02f,
                unknownCount = unknownCount,
                frameSequence = seq + 1L,
            ),
        )
    }
}
