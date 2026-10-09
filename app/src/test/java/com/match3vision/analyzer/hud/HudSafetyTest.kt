package com.match3vision.analyzer.hud

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.input.FiveMoveSession
import com.match3vision.analyzer.moves.PlayMoveRanker
import com.match3vision.analyzer.moves.TurnClock
import com.match3vision.analyzer.vision.TileColor
import org.junit.Test

/**
 * 0.26.1: PvP turn safety, score-legend digits, Time Left, and the player's card.
 */
class HudSafetyTest {
    private val white = HudReader.argb(255, 255, 255)

    @Test
    fun yourTurn_isRead_andOpponentTurnStops() {
        val your = frame()
        paintBar(your, 40, 140, 230)
        Glyphs.paint(your, 1080, 2400, 200, 1024, Glyphs.named("your"), white)
        paint(your, 1000, 1050, 890, 940, 180, 30, 40)
        val yours = HudReader.read(your, 1080, 2400)
        assertThat(yours.mode).isEqualTo("pvp")
        assertThat(yours.turnState).isEqualTo(HudObservation.TURN_YOUR)
        assertThat(yours.playerTurn()).isTrue()
        assertThat(TurnGate.refusal(yours)).isNull()

        val opponent = frame()
        paintBar(opponent, 140, 40, 55)
        Glyphs.paint(opponent, 1080, 2400, 160, 1024, Glyphs.named("opponent"), white)
        val theirs = HudReader.read(opponent, 1080, 2400)
        assertThat(theirs.mode).isEqualTo("pvp")
        assertThat(theirs.turnState).isEqualTo(HudObservation.TURN_OPPONENT)
        assertThat(TurnGate.refusal(theirs)).isEqualTo("STOP — Opponent's Turn")
        assertThat(TurnGate.allowsVerification(theirs)).isFalse()
    }

    @Test
    fun timeLeft_threeOrLess_usesTheFastScorer() {
        assertThat(TurnClock.skipLookahead("Time Left: 3")).isTrue()
        assertThat(TurnClock.skipLookahead("Time Left: 2")).isTrue()
        assertThat(TurnClock.skipLookahead("Time Left: 4")).isFalse()
        assertThat(TurnClock.skipLookahead("not detectable")).isFalse()

        val pixels = frame()
        paintBar(pixels, 40, 140, 230)
        val time = Glyphs.named("time")
        Glyphs.paint(pixels, 1080, 2400, 180, 1024, time, white)
        Glyphs.paint(pixels, 1080, 2400, 180 + time.width + 3, 1024, Glyphs.named("d3"), white)
        paint(pixels, 1000, 1050, 890, 940, 180, 30, 40)
        val hud = HudReader.read(pixels, 1080, 2400)
        assertThat(hud.mode).isEqualTo("pvp")
        assertThat(hud.timeLeftSeconds).isEqualTo(3)
        assertThat(hud.playerTurn()).isTrue()
        assertThat(TurnGate.refusal(hud)).isNull()
        assertThat(TurnClock.skipLookahead(hud.timer)).isTrue()
    }

    @Test
    fun unrecognizedPvp_doesNotStop_andLogsUnknown() {
        val pixels = frame()
        paint(pixels, 1000, 1050, 890, 940, 180, 30, 40)
        paint(pixels, HudReader.ACTIVATE_LEFT, HudReader.ACTIVATE_RIGHT, HudReader.ACTIVATE_TOP, HudReader.ACTIVATE_BOTTOM, 80, 180, 255)
        val hud = HudReader.read(pixels, 1080, 2400)
        assertThat(hud.mode).isEqualTo("solo")
        assertThat(hud.soloLayout).isTrue()
        assertThat(hud.playerTurn()).isFalse()
        assertThat(hud.hudState).isEqualTo(HudObservation.HUD_UNKNOWN)
        assertThat(TurnGate.refusal(hud)).isNull()
        assertThat(hud.log()).contains("hudState=UNKNOWN")
        assertThat(hud.log()).contains("opponentReds=")
        assertThat(hud.hudScores).contains("your=")
        assertThat(hud.hudScores).contains("opponent=")
        assertThat(hud.activateWord).isFalse()
        assertThat(SoloBooster.plan(hud, 1080, 2400, controlEnabled = true)).isNull()
    }

    @Test
    fun playerActivate_duringYourTurn_isTheOnlyPvpTap() {
        val pixels = frame()
        paintBar(pixels, 40, 140, 230)
        Glyphs.paint(pixels, 1080, 2400, 200, 1024, Glyphs.named("your"), white)
        Glyphs.paint(pixels, 1080, 2400, 80, 900, Glyphs.named("activate"), white)
        paint(pixels, 1000, 1050, 890, 940, 180, 30, 40)
        val hud = HudReader.read(pixels, 1080, 2400)
        assertThat(hud.playerTurn()).isTrue()
        assertThat(hud.activate).isEqualTo("yes")
        assertThat(hud.activateWord).isTrue()
        assertThat(hud.boosterFill).isEqualTo("ACTIVATE")
        val tap = SoloBooster.plan(hud, 1080, 2400, controlEnabled = true)
        assertThat(tap).isNotNull()
        assertThat(tap!!.x).isLessThan(400f)
    }

    @Test
    fun legendDigits_matchTheOwnerColors_andABlankLegendStaysOnePoint() {
        val pixels = frame()
        stampLegend(pixels, 280, TileColor.G, "d2", 40, 180, 60)
        stampLegend(pixels, 400, TileColor.O, "d3", 230, 120, 30)
        stampLegend(pixels, 520, TileColor.P, "d4", 170, 50, 210)
        stampLegend(pixels, 640, TileColor.Y, "d5", 240, 210, 40)
        for (i in 0 until 10) {
            val x = 390 + i * 64
            paint(pixels, x - 6, x + 6, 924, 936, 230, 230, 80)
        }
        val hud = HudReader.read(pixels, 1080, 2400)
        assertThat(hud.soloLayout).isTrue()
        assertThat(hud.legendPoints[TileColor.G]).isEqualTo(2)
        assertThat(hud.legendPoints[TileColor.O]).isEqualTo(3)
        assertThat(hud.legendPoints[TileColor.P]).isEqualTo(4)
        assertThat(hud.legendPoints[TileColor.Y]).isEqualTo(5)
        assertThat(hud.legendPoints.containsKey(TileColor.B)).isFalse()
        val yellow = PlayMoveRanker.gemScore(mapOf(TileColor.Y to 3), hud.legendPoints)
        assertThat(yellow).isEqualTo(3 * 5 * PlayMoveRanker.LEGEND_SCALE)

        val blank = frame()
        for (i in 0 until 10) {
            val x = 390 + i * 64
            paint(blank, x - 6, x + 6, 924, 936, 230, 230, 80)
        }
        val unread = HudReader.read(blank, 1080, 2400)
        assertThat(unread.legendPoints).isEmpty()
        assertThat(unread.legend).contains("legend default")
    }

    @Test
    fun multiplier_scalesBlue_andLowTimeKeepsTheFastOrder() {
        val plain = PlayMoveRanker.plyPoints(false, 3, 3, 0, false, blueMultiplier = 1)
        val boosted = PlayMoveRanker.plyPoints(false, 3, 3, 0, false, blueMultiplier = 6)
        val gems = 3 * PlayMoveRanker.GEM_POINTS
        assertThat(boosted - gems).isEqualTo((plain - gems) * 6)

        val palette = listOf(TileColor.R, TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O)
        val colors = Array(7) { row -> Array(7) { col -> palette[(row + col) % 6] } }
        colors[6][1] = TileColor.R
        colors[5][2] = TileColor.R
        val board = com.match3vision.analyzer.board.Board.fromColors(colors)
        val ranker = PlayMoveRanker()
        assertThat(ranker.rank(board).ordered).isNotEmpty()
        val fast = ranker.rankLookahead(
            board,
            hud = HudObservation.pvp(turnState = HudObservation.TURN_TIME, timeLeftSeconds = 3, multiplier = 6),
        )
        assertThat(fast.lookahead).isEqualTo("skipped-low-time")
        val thinking = ranker.rankLookahead(
            board,
            hud = HudObservation.pvp(turnState = HudObservation.TURN_YOUR, multiplier = 6),
        )
        assertThat(thinking.lookahead).isEqualTo("used")
        val unread = ranker.rankLookahead(board, hud = HudObservation.pvp(turnState = HudObservation.TURN_YOUR))
        assertThat(unread.lookahead).isEqualTo("used")
    }

    @Test
    fun opponentTurnDuringSettle_doesNotCountTheChange() {
        val session = FiveMoveSession()
        session.arm(0L)
        val permit = (session.requestDispatch(gates()) as FiveMoveSession.Decision.Go).permit
        assertThat(session.consumePermit(permit)).isTrue()
        session.noteGesture(
            FiveMoveSession.GestureFact(
                startedAtMs = 1_000L,
                nowMs = 1_100L,
                callbackCompleted = true,
                cancelled = false,
                cells = "0,0↔0,1",
                fromX = 1f,
                fromY = 1f,
                toX = 2f,
                toY = 1f,
                beforeHash = 5L,
                beforeUnknown = 0,
            ),
        )
        val stopped = session.onSettle(
            FiveMoveSession.SettleSample(
                nowMs = 1_800L,
                boardHash = 99L,
                diffFraction = 0f,
                frameFresh = true,
                roiPlausible = true,
                visionPass = true,
                unknownCount = 0,
                ownUi = false,
                a11yConnected = true,
                countBoardChange = false,
            ),
        )
        assertThat(stopped).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(session.verifiedCount).isEqualTo(0)
        assertThat(session.report()).contains("opponent's turn")
    }

    private fun gates() = FiveMoveSession.Gates(
        nowMs = 1_000L,
        msSinceCollapse = FiveMoveSession.MIN_POST_COLLAPSE_MS,
        a11yConnected = true,
        selfCheckMeasured = true,
        overlayCollapsed = true,
        overlayOutsideRoi = true,
        visionPass = true,
        frameFresh = true,
        ownUi = false,
        roiPlausible = true,
    )

    private fun frame(): IntArray = IntArray(1080 * 2400) { HudReader.argb(30, 10, 50) }

    private fun paint(pixels: IntArray, x0: Int, x1: Int, y0: Int, y1: Int, r: Int, g: Int, b: Int) {
        val color = HudReader.argb(r, g, b)
        for (y in y0..y1) {
            for (x in x0..x1) pixels[y * 1080 + x] = color
        }
    }

    private fun paintBar(pixels: IntArray, r: Int, g: Int, b: Int) {
        paint(pixels, HudText.TURN_LEFT, HudText.TURN_RIGHT, HudText.TURN_TOP, HudText.TURN_BOTTOM, r, g, b)
    }

    private fun stampLegend(pixels: IntArray, x: Int, color: TileColor, digit: String, r: Int, g: Int, b: Int) {
        paint(pixels, x, x + 28, 2160, 2188, r, g, b)
        Glyphs.paint(pixels, 1080, 2400, x + 8, 2168, Glyphs.named(digit), white)
        assertThat(color).isNotNull()
    }
}
