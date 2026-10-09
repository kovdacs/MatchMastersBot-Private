package com.match3vision.analyzer.hud

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.moves.PlayMoveRanker
import com.match3vision.analyzer.vision.RealFrameLoader
import com.match3vision.analyzer.vision.TileColor
import org.junit.Test

class HudReaderTest {
    @Test
    fun brightActivateAndDarkCircles_areSolo_andReady() {
        val pixels = frame()
        paint(pixels, HudReader.ACTIVATE_LEFT, HudReader.ACTIVATE_RIGHT, HudReader.ACTIVATE_TOP, HudReader.ACTIVATE_BOTTOM, 80, 180, 255)
        paintCircles(pixels, brightCount = 0)
        val hud = HudReader.read(pixels, 1080, 2400)
        assertThat(hud.soloLayout).isTrue()
        assertThat(hud.mode).isEqualTo("solo")
        assertThat(hud.activate).isEqualTo("yes")
        assertThat(hud.boosterTarget).isEqualTo("none")
        assertThat(hud.blueFactor).isEqualTo(1.0)
        assertThat(hud.moves).isEqualTo("bright=0/10 dark=10/10")
        assertThat(hud.timer).isEqualTo("not detectable")
        assertThat(hud.movesRemaining).isNull()
        assertThat(hud.activateWord).isFalse()
        assertThat(
            BoosterMonitor.mayTap(
                controlEnabled = false,
                activateWord = false,
                ourTurn = true,
                extraMoveAvailable = false,
                soloPositive = true,
                yourTurn = true,
                swipesVerified = 1,
                selfCheckMeasured = true,
            ),
        ).isFalse()
    }

    @Test
    fun opponentRedWithoutTurnText_isTreatedAsSolo_andDoesNotTap() {
        val pixels = frame()
        paint(pixels, HudReader.ACTIVATE_LEFT, HudReader.ACTIVATE_RIGHT, HudReader.ACTIVATE_TOP, HudReader.ACTIVATE_BOTTOM, 80, 180, 255)
        paint(pixels, 1000, 1050, 890, 940, 180, 30, 40)
        val hud = HudReader.read(pixels, 1080, 2400)
        assertThat(hud.soloLayout).isTrue()
        assertThat(hud.mode).isEqualTo("solo")
        assertThat(hud.hudState).isEqualTo(HudObservation.HUD_UNKNOWN)
        assertThat(hud.activateWord).isFalse()
        assertThat(SoloBooster.plan(hud, 1080, 2400, controlEnabled = true)).isNull()
    }

    @Test
    fun blankFrame_uncertainIsSolo_andDoesNotStopForMoves() {
        val hud = HudReader.read(frame(), 1080, 2400)
        assertThat(hud.soloLayout).isTrue()
        assertThat(hud.mode).isEqualTo("solo")
        assertThat(hud.hudState).isEqualTo(HudObservation.HUD_UNKNOWN)
        assertThat(hud.movesRemaining).isNull()
    }

    @Test
    fun contrastingMoveCircles_areSolo_withoutActivate() {
        val pixels = frame()
        paintCircles(pixels, brightCount = 10)
        val hud = HudReader.read(pixels, 1080, 2400)
        assertThat(hud.soloLayout).isTrue()
        assertThat(hud.mode).isEqualTo("solo")
        assertThat(hud.activate).isEqualTo("no")
        assertThat(hud.boosterTarget).isEqualTo("unknown")
        assertThat(hud.moves).isEqualTo("bright=10/10 dark=0/10")
        assertThat(hud.movesRemaining).isEqualTo(10)
        assertThat(hud.circlesBright).isEqualTo(10)
        assertThat(hud.activateWord).isFalse()
        assertThat(SoloBooster.plan(hud, 1080, 2400, controlEnabled = true)).isNull()
    }

    @Test
    fun tenBrightCircles_overrideOpponentReds_andLookaheadRuns() {
        val pixels = frame()
        paint(pixels, 994, 1063, 875, 947, 180, 30, 40)
        paintCircles(pixels, brightCount = 10)
        val hud = HudReader.read(pixels, 1080, 2400)
        val reds = hud.hudScores.substringAfter("opponentReds=").substringBefore(" ").toInt()
        assertThat(reds).isAtLeast(41)
        assertThat(hud.mode).isEqualTo("solo")
        assertThat(hud.soloLayout).isTrue()
        assertThat(hud.movesRemaining).isEqualTo(10)
        assertThat(hud.circlesBright).isEqualTo(10)
        assertThat(hud.circlesClassifiable).isTrue()
        assertThat(TurnGate.refusal(hud)).isNull()
        val palette = listOf(TileColor.R, TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O)
        val colors = Array(7) { row -> Array(7) { col -> palette[(row + col) % 6] } }
        colors[6][1] = TileColor.R
        colors[5][2] = TileColor.R
        val looked = PlayMoveRanker().rankLookahead(Board.fromColors(colors), hud = hud)
        assertThat(looked.lookahead).isEqualTo("used")
    }

    @Test
    fun ownerFrame_someDarkCircles_dropsTheBrightCount() {
        val loaded = RealFrameLoader.loadFromResource("hud_solo/f_06.png")
        assertThat(loaded).isNotNull()
        val frame = loaded!!
        val before = HudReader.read(frame.pixels, frame.width, frame.height)
        assertThat(before.circlesClassifiable).isTrue()
        assertThat(before.circlesBright).isEqualTo(9)
        val pixels = frame.pixels.copyOf()
        for (i in 0 until 4) {
            val x = HudReader.circleX(i)
            val y = HudReader.CIRCLE_ROW_Y
            paint(pixels, x - 8, x + 8, y - 8, y + 8, 20, 20, 30)
        }
        val after = HudReader.read(pixels, frame.width, frame.height)
        assertThat(after.circlesClassifiable).isTrue()
        assertThat(after.circlesBright).isEqualTo(5)
        assertThat(after.moves).isEqualTo("bright=5/10 dark=5/10")
    }

    @Test
    fun paintedRow_countsTheDarkCircles() {
        val pixels = frame()
        paintCircles(pixels, brightCount = 6)
        val hud = HudReader.read(pixels, 1080, 2400)
        assertThat(hud.circlesClassifiable).isTrue()
        assertThat(hud.circlesBright).isEqualTo(6)
        assertThat(hud.movesRemaining).isEqualTo(6)
        assertThat(hud.moves).isEqualTo("bright=6/10 dark=4/10")
    }

    private fun frame(): IntArray = IntArray(1080 * 2400) { HudReader.argb(30, 10, 50) }

    private fun paintCircles(pixels: IntArray, brightCount: Int) {
        for (i in 0 until HudReader.CIRCLE_COUNT) {
            val x = HudReader.circleX(i)
            val y = HudReader.CIRCLE_ROW_Y
            if (i < brightCount) {
                paint(pixels, x - 8, x + 8, y - 8, y + 8, 230, 230, 80)
            } else {
                paint(pixels, x - 8, x + 8, y - 8, y + 8, 20, 20, 30)
            }
        }
    }

    private fun paint(pixels: IntArray, x0: Int, x1: Int, y0: Int, y1: Int, r: Int, g: Int, b: Int) {
        val color = HudReader.argb(r, g, b)
        for (y in y0..y1) {
            for (x in x0..x1) pixels[y * 1080 + x] = color
        }
    }
}
