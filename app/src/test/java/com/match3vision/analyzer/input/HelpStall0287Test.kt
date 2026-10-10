package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.moves.PlayMoveRanker
import com.match3vision.analyzer.play.HelpPolicy
import com.match3vision.analyzer.play.HelpTargets
import com.match3vision.analyzer.play.PlayFlags
import com.match3vision.analyzer.vision.TileColor
import org.junit.After
import org.junit.Test

/**
 * 0.28.7: helps are back on, non-blocking. Replays the 0.28.5 live stall
 * (Hammer retapped every tick, "input disabled (live)", no swipe) and the
 * other ways a help can hang: prompt never shown, board never changes.
 */
class HelpStall0287Test {

    @After
    fun resetFlags() {
        PlayFlags.reset()
    }

    private val hammer = HelpPolicy.Choice("hammer", needsTarget = true, reason = "enables extra move")
    private val charged = HelpPolicy.Charges(hammer = 1, shuffle = 1, geometryVerified = true)

    @Test
    fun replay0285_hammerTapFailsEveryTick_onlyOneAttempt_andSwipesContinue() {
        assertThat(PlayFlags.helps).isTrue()
        val session = armed()
        var taps = 0
        var swipes = 0
        val failed = InputDispatchResult.Failed("TOCTOU recheck blocked dispatchGesture: input disabled (live)")
        // 0.28.5 log: a frame every ~250 ms for a minute, hammer chosen on each one.
        for (tick in 0 until 240) {
            val now = 1_000L + tick * 250L
            val choice = session.considerHelp(
                hasLegalMove = true,
                extraMoveAvailable = false,
                charges = charged,
                hammerMakesExtra = true,
            )
            val step = session.advanceHelp(now, false, true, choice, 400f, 1500f, boardLabel = 7L)
            if (step is FiveMoveSession.HelpGesture.Tap) {
                taps += 1
                assertThat(RecognizedTap.applyHelp(session, failed, now + 10)).isFalse()
            }
            if (step is FiveMoveSession.HelpGesture.Hold) {
                assertThat(session.helpConsumesTurn()).isFalse()
            }
            val go = session.requestDispatch(gates(now + 20, frameSequence = tick + 1L))
            if (go is FiveMoveSession.Decision.Go) {
                swipes += 1
                session.releaseUnusedPermit()
            }
        }
        assertThat(taps).isEqualTo(1)
        assertThat(swipes).isAtLeast(200)
        assertThat(session.unavailableHelps).containsExactly("hammer")
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(session.report()).contains("help unavailable id=hammer")
        assertThat(session.report()).contains("--- HELPS ---")
    }

    @Test
    fun promptNeverAppears_unavailableAfter4s_thenSwipe() {
        val session = armed()
        val tap = session.advanceHelp(1_000L, false, true, hammer, 400f, 1500f, boardLabel = 7L)
        assertThat(tap).isInstanceOf(FiveMoveSession.HelpGesture.Tap::class.java)
        val ok = InputDispatchResult.Dispatched(GestureSpec.tap(214f, 2292f, 120L), callbackCompleted = true)
        assertThat(RecognizedTap.applyHelp(session, ok, 1_050L)).isTrue()
        assertThat(session.advanceHelp(3_000L, false, true, null, 400f, 1500f, 7L))
            .isInstanceOf(FiveMoveSession.HelpGesture.Hold::class.java)
        assertThat(session.advanceHelp(1_000L + FiveMoveSession.HELP_PROMPT_MS + 1, false, true, null, 400f, 1500f, 7L))
            .isNull()
        assertThat(session.unavailableHelps).contains("hammer")
        assertThat(session.helpConsumesTurn()).isFalse()
        assertThat(session.advanceHelp(60_000L, false, true, hammer, 400f, 1500f, 7L)).isNull()
        assertThat(session.requestDispatch(gates(60_100L, 9L))).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
    }

    @Test
    fun boardUnchangedAfterPick_unavailableWithin4s_thenSwipe() {
        val session = armed()
        session.advanceHelp(1_000L, false, true, hammer, 400f, 1500f, boardLabel = 7L)
        val ok = InputDispatchResult.Dispatched(GestureSpec.tap(214f, 2292f, 120L), callbackCompleted = true)
        RecognizedTap.applyHelp(session, ok, 1_050L)
        val pick = session.advanceHelp(1_500L, true, true, null, 400f, 1500f, 7L)
        assertThat(pick).isInstanceOf(FiveMoveSession.HelpGesture.Tap::class.java)
        RecognizedTap.applyHelp(session, ok, 1_550L)
        // Playable but identical board: not "settled".
        assertThat(session.advanceHelp(2_500L, false, true, null, null, null, 7L))
            .isInstanceOf(FiveMoveSession.HelpGesture.Hold::class.java)
        assertThat(session.advanceHelp(1_500L + FiveMoveSession.HELP_SETTLE_MS + 1, false, true, null, null, null, 7L))
            .isNull()
        assertThat(session.unavailableHelps).contains("hammer")
        assertThat(session.helpNote).contains("board unchanged")
        assertThat(session.helpConsumesTurn()).isFalse()
        assertThat(session.requestDispatch(gates(6_000L, 9L))).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
        assertThat(FiveMoveSession.HELP_SETTLE_MS).isAtMost(4_000L)
    }

    @Test
    fun shuffleThatChangesTheBoard_settles_andIsNotRetriedInTheSameStall() {
        val session = armed()
        val shuffle = HelpPolicy.Choice("shuffle", needsTarget = false, reason = "no legal move")
        assertThat(session.advanceHelp(1_000L, false, true, shuffle, null, null, boardLabel = 7L))
            .isInstanceOf(FiveMoveSession.HelpGesture.Tap::class.java)
        val ok = InputDispatchResult.Dispatched(GestureSpec.tap(78f, 2292f, 120L), callbackCompleted = true)
        assertThat(RecognizedTap.applyHelp(session, ok, 1_050L)).isTrue()
        assertThat(session.helpConsumesTurn()).isTrue()
        assertThat(session.advanceHelp(1_400L, false, false, null, null, null, 8L))
            .isInstanceOf(FiveMoveSession.HelpGesture.Hold::class.java)
        assertThat(session.advanceHelp(2_000L, false, true, null, null, null, 8L)).isNull()
        assertThat(session.helpNote).contains("settled")
        assertThat(session.unavailableHelps).isEmpty()
        val again = session.considerHelp(hasLegalMove = false, extraMoveAvailable = false, charges = charged)
        assertThat(again).isNull()
    }

    @Test
    fun policy_onlyOnOurTurn_boosterFirst_noHammerWhenExtraExists() {
        PlayFlags.helps = true
        assertThat(HelpPolicy.choose(true, false, charged, 0, hammerMakesExtra = true, ourTurn = false)).isNull()
        assertThat(HelpPolicy.choose(true, false, charged, 0, hammerMakesExtra = true, boosterReady = true)).isNull()
        assertThat(HelpPolicy.choose(true, true, charged, 0, hammerMakesExtra = true)).isNull()
        assertThat(HelpPolicy.choose(true, false, charged, 0, hammerMakesExtra = false, allWeak = false)).isNull()
        assertThat(HelpPolicy.choose(true, false, charged, 0, allWeak = true)!!.reason).isEqualTo("all moves weak")
        assertThat(HelpPolicy.choose(false, false, charged, 0)!!.id).isEqualTo("shuffle")
        assertThat(HelpPolicy.choose(false, false, charged, 0, unavailable = setOf("shuffle"))).isNull()
        val empty = HelpPolicy.Charges(hammer = 0, shuffle = 0, geometryVerified = true)
        assertThat(HelpPolicy.choose(false, false, empty, 0, hammerMakesExtra = true)).isNull()
    }

    @Test
    fun hammerTarget_createsAnExtraMove_onlyOnAFullyKnownBoard() {
        val palette = arrayOf(TileColor.B, TileColor.P, TileColor.O, TileColor.Y, TileColor.G)
        val colors = Array(Board.SIZE) { r -> Array(Board.SIZE) { c -> palette[(r * 2 + c) % 5] } }
        colors[6][0] = TileColor.R
        colors[6][1] = TileColor.R
        colors[6][2] = TileColor.G
        colors[6][3] = TileColor.R
        colors[5][2] = TileColor.Y
        colors[4][2] = TileColor.R
        val board = Board.fromColors(colors)
        assertThat(PlayMoveRanker().rank(board).ordered.none { it.extraMove }).isTrue()
        val target = HelpTargets.hammerForExtraMove(board)
        assertThat(target).isNotNull()
        val tile = board.get(target!!.row, target.col)
        assertThat(tile.visible).isTrue()
        assertThat(tile.color).isNotEqualTo(TileColor.UNKNOWN)

        colors[0][0] = TileColor.UNKNOWN
        assertThat(HelpTargets.hammerForExtraMove(Board.fromColors(colors))).isNull()
    }

    @Test
    fun ownerStatus_helpFailure_isHungarian_andNotAStop() {
        val text = com.match3vision.analyzer.overlay.OwnerStatus.hu(
            "help unavailable id=hammer reason=board unchanged (play continues)",
        )
        assertThat(text).contains("folytatom")
        assertThat(text).doesNotContain("leálltam")
        assertThat(com.match3vision.analyzer.overlay.OwnerStatus.hu("help waiting for pick a piece"))
            .contains("Segítséget")
    }

    private fun armed(): FiveMoveSession {
        PlayFlags.helps = true
        val session = FiveMoveSession()
        session.arm(0L)
        return session
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
}
