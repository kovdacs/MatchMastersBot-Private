package com.match3vision.analyzer.hud

import com.google.common.truth.Truth.assertThat
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
        assertThat(BoosterMonitor.mayTap(hud.soloLayout, hud.activate == "yes", controlEnabled = false)).isFalse()
    }

    @Test
    fun opponentRedGem_isNotSolo_evenIfTheLeftSideIsBright() {
        val pixels = frame()
        paint(pixels, HudReader.ACTIVATE_LEFT, HudReader.ACTIVATE_RIGHT, HudReader.ACTIVATE_TOP, HudReader.ACTIVATE_BOTTOM, 80, 180, 255)
        paint(pixels, 1000, 1050, 890, 940, 180, 30, 40)
        val hud = HudReader.read(pixels, 1080, 2400)
        assertThat(hud.soloLayout).isFalse()
        assertThat(hud.mode).isEqualTo("pvp")
        assertThat(SoloBooster.plan(hud, 1080, 2400, controlEnabled = true)).isNull()
    }

    @Test
    fun blankFrame_isUnknown() {
        val hud = HudReader.read(frame(), 1080, 2400)
        assertThat(hud.soloLayout).isFalse()
        assertThat(hud.mode).isEqualTo(HudObservation.MODE_UNKNOWN)
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
        assertThat(hud.movesRemaining).isNull()
        assertThat(SoloBooster.plan(hud, 1080, 2400, controlEnabled = true)).isNull()
    }

    private fun frame(): IntArray = IntArray(1080 * 2400) { HudReader.argb(30, 10, 50) }

    private fun paint(pixels: IntArray, x0: Int, x1: Int, y0: Int, y1: Int, r: Int, g: Int, b: Int) {
        val color = HudReader.argb(r, g, b)
        for (y in y0..y1) {
            for (x in x0..x1) pixels[y * 1080 + x] = color
        }
    }
}
