package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.hud.HudObservation
import com.match3vision.analyzer.overlay.OwnerStatus
import com.match3vision.analyzer.vision.SpecialType
import com.match3vision.analyzer.vision.TileColor
import org.junit.Test

/** Owner failures from 0.26.5 through 0.26.7 must keep playing. */
class PlaySettle0270Test {
    @Test
    fun alternatingPixelDiff_withTheSameLabels_verifies() {
        val session = armed()
        val flickering = session.onSettle(pass(nowMs = 3_000L, hash = 9L, diff = 0.45f, sequence = 2L))
        assertThat(flickering).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat((flickering as FiveMoveSession.Decision.Hold).reason).contains("first stable")
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.SETTLING)
        val settled = session.onSettle(pass(nowMs = 3_400L, hash = 9L, diff = 0.02f, sequence = 3L))
        assertThat(settled).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.verifiedCount).isEqualTo(1)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(session.movesSnapshot().single().verification).contains("labels stable")
        assertThat(session.movesSnapshot().single().verification).contains("vision PASS")
        assertThat(session.report()).contains("settle move")
    }

    @Test
    fun oneFramePerSecond_withAgesOfSeveralSeconds_keepsPlaying() {
        val session = armed()
        val cadence = 1_000L
        val post = FiveMoveSession.scaled(FiveMoveSession.POST_SWIPE_MS, cadence)
        val first = session.onSettle(
            pass(
                nowMs = post,
                hash = 9L,
                diff = 0.45f,
                sequence = 2L,
                ageMs = 2_000L,
                cadenceMs = cadence,
            ),
        )
        assertThat(first).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.SETTLING)
        val verified = session.onSettle(
            pass(
                nowMs = post + cadence,
                hash = 9L,
                diff = 0.02f,
                sequence = 3L,
                ageMs = 4_900L,
                cadenceMs = cadence,
            ),
        )
        assertThat(verified).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.verifiedCount).isEqualTo(1)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(session.requestDispatch(gates(post + cadence + 100L))).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
    }

    @Test
    fun cascadePastTwentySeconds_doesNotStop() {
        val session = armed()
        var last: FiveMoveSession.Decision = FiveMoveSession.Decision.Hold("none")
        for (second in 1..25) {
            last = session.onSettle(
                pass(
                    nowMs = 1_000L * second,
                    hash = 100L + second,
                    diff = if (second % 2 == 0) 0.45f else 0.02f,
                    sequence = second.toLong(),
                    ageMs = 4_000L,
                    cadenceMs = 1_000L,
                    visionPass = second < 20,
                ),
            )
            assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.SETTLING)
        }
        assertThat(last).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.verifiedCount).isEqualTo(0)
        assertThat((last as FiveMoveSession.Decision.Hold).reason).doesNotContain("per-move")
        val settled = session.onSettle(
            pass(nowMs = 26_000L, hash = 9L, diff = 0.50f, sequence = 30L, ageMs = 3_000L, cadenceMs = 1_000L),
        )
        val verified = session.onSettle(
            pass(nowMs = 28_000L, hash = 9L, diff = 0.45f, sequence = 31L, ageMs = 4_500L, cadenceMs = 1_000L),
        )
        assertThat(settled).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(verified).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.verifiedCount).isEqualTo(1)
        assertThat(session.stopReason).isEmpty()
    }

    @Test
    fun settleWait_stopsInHungarian_andASpecialDoesNotChangeTheLabel() {
        val session = armed()
        val expired = session.onSettle(pass(nowMs = FiveMoveSession.SETTLE_WAIT_MS, hash = 9L, diff = 0.5f, sequence = 4L, visionPass = false))
        assertThat(expired).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(OwnerStatus.hu(session.stopReason)).isEqualTo("A tábla nem állt meg – leálltam")
        val plain = Board.fromColors(Array(7) { row -> Array(7) { col -> TileColor.R } })
        val glowing = plain.setCopy(4, 3, plain.get(4, 3).copy(special = SpecialType.TWO_WAY_ARROW))
        assertThat(plain.labelsAgree(glowing)).isTrue()
        assertThat(plain.labelHash()).isEqualTo(glowing.labelHash())
    }

    @Test
    fun twoUnknownMoves_stop_andSoloNeverDoes() {
        val session = FiveMoveSession()
        session.arm(0L)
        assertThat(session.notePlayHud(PlayGate.UNKNOWN, 10L)).isNull()
        session.noteDispatchedHud(PlayGate.UNKNOWN)
        session.noteDispatchedHud(PlayGate.UNKNOWN)
        val stopped = session.notePlayHud(PlayGate.UNKNOWN, 20L)
        assertThat(stopped).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(OwnerStatus.hu((stopped as FiveMoveSession.Decision.Stop).reason))
            .isEqualTo("Nem ismerem a képernyőt – leálltam")

        val solo = FiveMoveSession()
        solo.arm(0L)
        repeat(4) {
            assertThat(solo.notePlayHud(PlayGate.OURS, 10L)).isNull()
            solo.noteDispatchedHud(PlayGate.OURS)
        }
        assertThat(solo.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        val menu = FiveMoveSession()
        menu.arm(0L)
        val popped = menu.notePlayHud(PlayGate.MENU, 10L) as FiveMoveSession.Decision.Stop
        assertThat(OwnerStatus.hu(popped.reason)).isEqualTo("Menü vagy felugró ablak – leálltam")
        val dim = FiveMoveSession()
        dim.arm(0L)
        assertThat(dim.notePlayHud(PlayGate.DIMMED, 10L)).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(dim.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
    }

    @Test
    fun circlesUnchangedOnANewBoard_isLoggedAsAnExtraMove() {
        val session = FiveMoveSession()
        session.arm(0L)
        val permit = (session.requestDispatch(gates(1_000L)) as FiveMoveSession.Decision.Go).permit
        session.consumePermit(permit)
        session.noteGesture(
            FiveMoveSession.GestureFact(
                startedAtMs = 1_000L,
                nowMs = 1_100L,
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
                beforeCircles = 8,
            ),
        )
        session.onSettle(pass(nowMs = 3_600L, hash = 9L, diff = 0.4f, sequence = 2L, circles = 8))
        val verified = session.onSettle(pass(nowMs = 4_000L, hash = 9L, diff = 0.4f, sequence = 3L, circles = 8))
        assertThat(verified).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.movesSnapshot().single().verification).contains("extra-move circles unchanged")
        assertThat(session.swipesVerified).isEqualTo(1)
        assertThat(session.swipesDispatched).isEqualTo(1)
    }

    @Test
    fun opponentTurn_isStillTheOnlyTurnStop() {
        val hud = HudObservation.solo(movesRemaining = 8)
        assertThat(PlayGate.kind(hud, visionPass = true, dimmed = false)).isEqualTo(PlayGate.OURS)
        val opponent = HudObservation.pvp(turnState = HudObservation.TURN_OPPONENT)
        assertThat(PlayGate.kind(opponent, visionPass = true, dimmed = false)).isEqualTo(PlayGate.OPPONENT)
        val dark = IntArray(40 * 40) { 0xff050505.toInt() }
        assertThat(PlayGate.dimmed(dark, 40, 40, 0, 0, 40, 40)).isTrue()
        val bright = IntArray(40 * 40) { 0xfff0f0f0.toInt() }
        assertThat(PlayGate.dimmed(bright, 40, 40, 0, 0, 40, 40)).isFalse()
    }

    private fun armed(): FiveMoveSession {
        val session = FiveMoveSession()
        session.arm(0L)
        val permit = (session.requestDispatch(gates(100L)) as FiveMoveSession.Decision.Go).permit
        session.consumePermit(permit)
        session.noteGesture(
            FiveMoveSession.GestureFact(
                startedAtMs = 0L,
                nowMs = 100L,
                callbackCompleted = true,
                cancelled = false,
                cells = "(0,3)↔(0,4)",
                fromX = 1f,
                fromY = 1f,
                toX = 2f,
                toY = 1f,
                beforeHash = 1L,
                beforeUnknown = 0,
                beforeLabel = 1L,
            ),
        )
        return session
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
        frameSequence = 40L,
    )

    private fun pass(
        nowMs: Long,
        hash: Long,
        diff: Float,
        sequence: Long,
        ageMs: Long = 100L,
        cadenceMs: Long = 0L,
        visionPass: Boolean = true,
        circles: Int? = null,
    ) = FiveMoveSession.SettleSample(
        nowMs = nowMs,
        boardHash = hash,
        diffFraction = diff,
        frameFresh = true,
        roiPlausible = true,
        visionPass = visionPass,
        unknownCount = if (visionPass) 0 else 8,
        ownUi = false,
        a11yConnected = true,
        frameSequence = sequence,
        frameAgeMs = ageMs,
        cadenceMedianMs = cadenceMs,
        circlesBright = circles,
        circlesClassifiable = circles != null,
    )
}
