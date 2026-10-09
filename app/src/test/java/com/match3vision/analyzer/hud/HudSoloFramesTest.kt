package com.match3vision.analyzer.hud

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.vision.RealFrameLoader
import org.junit.Test

/**
 * Owner solo recording, 1080×2400. ACTIVATE flashes, so one frame is not enough.
 * The word shape over the last five frames is the positive.
 */
class HudSoloFramesTest {
    @Test
    fun brightFrame_readsYourTurn_andLogsTheActivateWord() {
        val frame = RealFrameLoader.loadFromResource("hud_solo/f_015.png")
        assertThat(frame).isNotNull()
        val hud = HudReader.read(frame!!.pixels, frame.width, frame.height)
        assertThat(hud.mode).isEqualTo("solo")
        assertThat(hud.soloLayout).isTrue()
        assertThat(hud.soloPositive).isTrue()
        assertThat(hud.turnState).isEqualTo(HudObservation.TURN_YOUR)
        assertThat(hud.playerTurn()).isTrue()
        assertThat(hud.circlesClassifiable).isTrue()
        assertThat(hud.movesRemaining).isGreaterThan(0)
        assertThat(hud.activateFloor).isEqualTo(HudText.PHRASE_FLOOR)
        assertThat(hud.activateScore).isAtLeast(HudText.PHRASE_FLOOR)
        assertThat(hud.activateRect).contains("LTRB(")
        assertThat(hud.log()).contains("activateScore=")
        assertThat(hud.log()).contains("activateFloor=")
        assertThat(hud.activateWord).isTrue()
    }

    @Test
    fun flashingActivate_isPositiveAcrossTheLastFiveFrames() {
        val names = listOf("f_160.png", "f_161.png", "f_162.png", "f_163.png", "f_164.png")
        val pulse = HudPulse()
        var sawWord = false
        names.forEach { name ->
            val frame = RealFrameLoader.loadFromResource("hud_solo/$name")
            assertThat(frame).isNotNull()
            val raw = HudReader.read(frame!!.pixels, frame.width, frame.height)
            val read = pulse.apply(raw)
            if (read.activateWord) sawWord = true
            assertThat(read.turnState).isEqualTo(HudObservation.TURN_YOUR)
            assertThat(read.soloPositive).isTrue()
        }
        assertThat(sawWord).isTrue()
        assertThat(pulse.apply(HudObservation.solo(activateWord = false)).activateWord).isTrue()
    }

    @Test
    fun ownerFrames_f03AndF06_readTheActivateWord() {
        listOf("hud_solo/f_03.png", "hud_solo/f_06.png").forEach { name ->
            val frame = RealFrameLoader.loadFromResource(name)
            assertThat(frame).isNotNull()
            val hud = HudReader.read(frame!!.pixels, frame.width, frame.height)
            assertThat(hud.activateScore).isAtLeast(HudText.PHRASE_FLOOR)
            assertThat(hud.activateWord).isTrue()
            assertThat(hud.activateRect).contains("LTRB(")
            assertThat(hud.soloPositive).isTrue()
        }
    }

    @Test
    fun fullBarOnThreeOfFiveFrames_armsTheBoosterWithoutAWord() {
        val pulse = HudPulse()
        val full = HudObservation.solo(activateWord = false).copy(barFull = true, activateWord = false)
        assertThat(pulse.apply(full).boosterReady).isFalse()
        assertThat(pulse.apply(full).boosterReady).isFalse()
        assertThat(pulse.apply(full).boosterReady).isTrue()
    }

    @Test
    fun pulse_keepsARecentWord_andAnOpponentTurnClearsIt() {
        val pulse = HudPulse()
        val off = HudObservation.solo(activateWord = false)
        val on = HudObservation.solo(activateWord = true)
        assertThat(pulse.apply(off).activateWord).isFalse()
        assertThat(pulse.apply(on).activateWord).isTrue()
        assertThat(pulse.apply(off).activateWord).isTrue()
        val opponent = HudObservation.pvp(turnState = HudObservation.TURN_OPPONENT)
        assertThat(pulse.apply(opponent).activateWord).isFalse()
        assertThat(pulse.apply(off).activateWord).isFalse()
    }
}
