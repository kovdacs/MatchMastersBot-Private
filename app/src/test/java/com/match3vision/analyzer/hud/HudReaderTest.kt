package com.match3vision.analyzer.hud

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.moves.PlayMoveRanker
import com.match3vision.analyzer.vision.TileColor
import org.junit.Test

class HudReaderTest {
    @Test
    fun brightActivateAndDarkCircles_areSolo_andReady() {
        val pixels = frame()
        paint(pixels, HudReader.ACTIVATE_LEFT, HudReader.ACTIVATE_RIGHT, HudReader.ACTIVATE_TOP, HudReader.ACTIVATE_BOTTOM, 80, 180, 255)
        for (i in 0 until 10) {
            val x = 390 + i * 64
            paint(pixels, x - 6, x + 6, 924, 936, 20, 20, 30)
        }
        val hud = HudReader.read(pixels, 1080, 2400)
        assertThat(hud.soloLayout).isTrue()
        assertThat(hud.mode).isEqualTo("solo")
        assertThat(hud.activate).isEqualTo("yes")
        assertThat(hud.boosterTarget).isEqualTo("none")
        assertThat(hud.blueFactor).isEqualTo(com.match3vision.analyzer.moves.PlayMoveRanker.FULL_BAR_BLUE_FACTOR)
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
        for (i in 0 until 10) {
            val x = 390 + i * 64
            paint(pixels, x - 6, x + 6, 924, 936, 230, 230, 80)
        }
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
        for (i in 0 until 10) {
            val x = 390 + i * 64
            paint(pixels, x - 6, x + 6, 924, 936, 230, 230, 80)
        }
        paint(pixels, 994, 1063, 875, 947, 180, 30, 40)
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

    private fun frame(): IntArray = IntArray(1080 * 2400) { HudReader.argb(30, 10, 50) }

    private fun paint(pixels: IntArray, x0: Int, x1: Int, y0: Int, y1: Int, r: Int, g: Int, b: Int) {
        val color = HudReader.argb(r, g, b)
        for (y in y0..y1) {
            for (x in x0..x1) pixels[y * 1080 + x] = color
        }
    }
}
