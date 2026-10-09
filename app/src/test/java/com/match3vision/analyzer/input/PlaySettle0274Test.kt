package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.hud.HudObservation
import org.junit.Test

class PlaySettle0274Test {
    @Test
    fun staleBoosterFrame_waitsThenSends_andGivesUpWithoutStopping() {
        val limit = FiveMoveSession.SETTLE_FRAME_AGE_MS
        val stale = FreshFrameDispatch.booster(
            elapsedMs = 0L,
            ageMs = 5_150L,
            ageLimitMs = limit,
            gatesPass = true,
            showsActivateOrYourTurn = true,
            recheckAllow = false,
        )
        assertThat(stale).isEqualTo(FreshFrameDispatch.Booster.WAIT)
        val ready = FreshFrameDispatch.booster(
            elapsedMs = 1_000L,
            ageMs = 200L,
            ageLimitMs = limit,
            gatesPass = true,
            showsActivateOrYourTurn = true,
            recheckAllow = true,
        )
        assertThat(ready).isEqualTo(FreshFrameDispatch.Booster.SEND)
        val notOurs = FreshFrameDispatch.booster(
            elapsedMs = 2_000L,
            ageMs = 200L,
            ageLimitMs = limit,
            gatesPass = true,
            showsActivateOrYourTurn = false,
            recheckAllow = true,
        )
        assertThat(notOurs).isEqualTo(FreshFrameDispatch.Booster.WAIT)
        val gaveUp = FreshFrameDispatch.booster(
            elapsedMs = FreshFrameDispatch.BOOSTER_GIVE_UP_MS,
            ageMs = 200L,
            ageLimitMs = limit,
            gatesPass = true,
            showsActivateOrYourTurn = true,
            recheckAllow = true,
        )
        assertThat(gaveUp).isEqualTo(FreshFrameDispatch.Booster.GIVE_UP)
        assertThat(FreshFrameDispatch.showsActivate(HudObservation.solo(activateWord = true))).isTrue()
        assertThat(FreshFrameDispatch.showsActivate(HudObservation.solo())).isFalse()

        val session = FiveMoveSession()
        session.arm(0L)
        session.giveUpBooster()
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(session.stopReason).isEmpty()
        assertThat(session.requestDispatch(gates(1_000L))).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
    }

    @Test
    fun postSwipeScale_capsAtTwo_soTheWaitIsAtMostFiveOrEightSeconds() {
        assertThat(FiveMoveSession.scaled(FiveMoveSession.POST_SWIPE_MS, 200L)).isEqualTo(2_500L)
        assertThat(FiveMoveSession.scaled(FiveMoveSession.POST_SWIPE_MS, 1_000L)).isEqualTo(5_000L)
        assertThat(FiveMoveSession.scaled(FiveMoveSession.POST_SWIPE_BIG_MS, 1_000L)).isEqualTo(8_000L)
        assertThat(FiveMoveSession.scaled(FiveMoveSession.POST_SWIPE_MS, 50_000L)).isEqualTo(5_000L)
        assertThat(FiveMoveSession.scaled(FiveMoveSession.POST_SWIPE_BIG_MS, 50_000L)).isEqualTo(8_000L)

        val session = FiveMoveSession()
        session.arm(0L)
        val go = session.requestDispatch(gates(0L)) as FiveMoveSession.Decision.Go
        session.consumePermit(go.permit)
        session.noteGesture(
            FiveMoveSession.GestureFact(
                startedAtMs = 0L,
                nowMs = 100L,
                callbackCompleted = true,
                cancelled = false,
                cells = "(0,0)↔(0,1)",
                fromX = 1f,
                fromY = 1f,
                toX = 2f,
                toY = 1f,
                beforeHash = 1L,
                beforeUnknown = 0,
                beforeLabel = 1L,
                longSettle = true,
            ),
        )
        val waiting = session.onSettle(pass(nowMs = 3_000L, hash = 9L, sequence = 2L, cadenceMs = 50_000L))
        assertThat(waiting).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat((waiting as FiveMoveSession.Decision.Hold).reason).contains("waiting 8000ms")
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.SETTLING)
        assertThat(session.stopReason).isEmpty()
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

    private fun pass(nowMs: Long, hash: Long, sequence: Long, cadenceMs: Long) = FiveMoveSession.SettleSample(
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
        cadenceMedianMs = cadenceMs,
    )
}
