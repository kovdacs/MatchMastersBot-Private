package com.match3vision.analyzer.play

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.hud.HudObservation
import com.match3vision.analyzer.input.FiveMoveSession
import com.match3vision.analyzer.input.PlayGate
import com.match3vision.analyzer.input.SwipeGuard
import com.match3vision.analyzer.moves.PlayMoveRanker
import com.match3vision.analyzer.overlay.OwnerStatus
import com.match3vision.analyzer.vision.TileColor
import com.match3vision.analyzer.vision.TileShape
import org.junit.Test

/**
 * Speed and repeatable ACTIVATE apply in every mode. Showdown is the example:
 * a timed board still uses the timer stop, and it does not wait after a swipe.
 */
class OwnerSpeed0283Test {
    @Test
    fun twoPassFrames_swipeWithoutAPostGestureWait() {
        val soon = SwipeGuard.motionBlock(
            ageMs = 200L,
            previousLabel = 4L,
            labelHash = 4L,
            capturedAtMs = 1_250L,
            previousCapturedAtMs = 1_000L,
            lastGestureAtMs = 1_200L,
        )
        assertThat(soon).isNull()
        val close = SwipeGuard.motionBlock(
            ageMs = 200L,
            previousLabel = 4L,
            labelHash = 4L,
            capturedAtMs = 1_200L,
            previousCapturedAtMs = 1_000L,
            lastGestureAtMs = 0L,
        )
        assertThat(close).contains("too close")
        assertThat(SwipeGuard.MIN_AGREE_GAP_MS).isEqualTo(250L)
    }

    @Test
    fun threeUnknownCells_stillPlayMovesThatAvoidThem() {
        val palette = listOf(TileColor.R, TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O)
        val colors = Array(7) { row -> Array(7) { col -> palette[(row + col) % 6] } }
        colors[0][0] = TileColor.R
        colors[0][1] = TileColor.R
        colors[0][2] = TileColor.R
        colors[0][3] = TileColor.B
        colors[1][3] = TileColor.R
        var board = Board.fromColors(colors)
        val unknowns = listOf(4 to 0, 4 to 1, 5 to 6)
        for ((row, col) in unknowns) {
            val tile = board.get(row, col)
            board = board.setCopy(
                row,
                col,
                tile.copy(color = TileColor.UNKNOWN, shape = TileShape.UNKNOWN),
            )
        }
        val ranked = PlayMoveRanker().rank(board)
        assertThat(ranked.ordered).isNotEmpty()
        val blocked = unknowns.toSet()
        for (candidate in ranked.ordered) {
            val cells = setOf(candidate.move.r1 to candidate.move.c1, candidate.move.r2 to candidate.move.c2)
            assertThat(cells.intersect(blocked)).isEmpty()
        }
        assertThat(
            PlayUnknown.forPlay(false, 3, 0.99f, "HOLD: unknownCount 3 > 1"),
        ).isTrue()
        assertThat(
            PlayUnknown.forPlay(false, 4, 0.99f, "HOLD: unknownCount 4 > 1"),
        ).isFalse()
    }

    @Test
    fun timedHundredSeconds_swipesAtLeastTwentyFive_andRetapsActivate() {
        val session = FiveMoveSession()
        session.arm(0L)
        val startHud = HudObservation.pvp(turnState = HudObservation.TURN_TIME, timeLeftSeconds = 100)
        repeat(FiveMoveSession.MODE_CONFIRM_FRAMES) { session.observeHud(startHud) }
        assertThat(session.screenMode).isEqualTo(PlayMode.TIMER)
        var seq = 1L
        var hash = 10L
        var label = hash
        var swipes = 0
        var boosterTaps = 0
        var stopped = false
        for (second in 0 until 100) {
            val now = 1_000L + second * 1_000L
            if (session.phase != FiveMoveSession.Phase.RUNNING) break
            val timeLeft = if (second >= 98) 0 else 100 - second
            session.observeHud(
                HudObservation.pvp(turnState = HudObservation.TURN_TIME, timeLeftSeconds = timeLeft),
            )
            val kind = if (second % 11 == 0) PlayGate.UNKNOWN else PlayGate.OURS
            val hud = session.notePlayHud(kind, now)
            if (hud is FiveMoveSession.Decision.Stop) {
                stopped = true
                break
            }
            val circles = session.noteCircles(true, 0, true, now, seq)
            check(circles == null) { "timed play stopped on circles: ${session.stopReason}" }
            val captured = now - 200L
            val block = session.considerSwipeFrame(200L, label, now, captured)
            val activate = second % 25 in 0..2
            if (!activate && boosterTaps > 0) {
                session.noteBoosterBoard(activateVisible = false, barFull = false)
            }
            if (block == null) {
                val step = session.considerBoosterFrame(
                    nowMs = now,
                    activateVisible = activate,
                    barFull = activate,
                    canSendNow = true,
                    provenNoTarget = true,
                )
                if (step == FiveMoveSession.BoosterStep.TAP) {
                    session.noteBoosterTap(180f, 940f, FiveMoveSession.BOOSTER_TAP_MS)
                    boosterTaps += 1
                }
                seq += 1
                val go = session.requestDispatch(gates(now, seq))
                if (go is FiveMoveSession.Decision.Go) {
                    session.consumePermit(go.permit)
                    val before = hash
                    session.noteGesture(gesture(now, before, seq))
                    hash += 1
                    label = hash
                    seq += 1
                    session.onSettle(pass(now + 250L, hash, seq))
                    seq += 1
                    session.onSettle(pass(now + 500L, hash, seq))
                    check(session.phase == FiveMoveSession.Phase.RUNNING) {
                        "settle did not finish: ${session.stopReason} phase=${session.phase}"
                    }
                    swipes += 1
                }
            }
        }
        assertThat(swipes).isAtLeast(25)
        assertThat(boosterTaps).isAtLeast(3)
        assertThat(stopped).isTrue()
        assertThat(session.stopReason).contains("time out")
        assertThat(OwnerStatus.hu(session.stopReason)).isEqualTo("Játék vége")
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.STOPPED)
    }

    private fun gates(nowMs: Long, frameSequence: Long) = FiveMoveSession.Gates(
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
        frameSequence = frameSequence,
    )

    private fun gesture(started: Long, beforeHash: Long, sequence: Long) = FiveMoveSession.GestureFact(
        startedAtMs = started,
        nowMs = started + 40L,
        callbackCompleted = true,
        cancelled = false,
        cells = "(0,0)↔(0,1)",
        fromX = 1f,
        fromY = 1f,
        toX = 2f,
        toY = 1f,
        beforeHash = beforeHash,
        beforeUnknown = 0,
        beforeLabel = beforeHash,
        beforeCircles = 0,
        swipeSequence = sequence,
    )

    private fun pass(nowMs: Long, hash: Long, sequence: Long) = FiveMoveSession.SettleSample(
        nowMs = nowMs,
        boardHash = hash,
        diffFraction = 0.02f,
        frameFresh = true,
        roiPlausible = true,
        visionPass = true,
        unknownCount = 0,
        ownUi = false,
        a11yConnected = true,
        frameSequence = sequence,
        labelHash = hash,
        frameAgeMs = 100L,
        circlesBright = 0,
        circlesClassifiable = true,
    )
}
