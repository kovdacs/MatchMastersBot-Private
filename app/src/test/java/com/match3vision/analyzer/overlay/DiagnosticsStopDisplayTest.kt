package com.match3vision.analyzer.overlay

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.input.AutoPlayController
import com.match3vision.analyzer.input.AutoPlayTrace
import org.junit.After
import org.junit.Test

class DiagnosticsStopDisplayTest {

    @After
    fun tearDown() {
        AutoPlayTrace.clear()
    }

    @Test
    fun running_dropsStaleAccessibilityDisconnected() {
        val shown = DiagnosticsStopDisplay.resolve(
            mode = AutoPlayController.Mode.RUNNING,
            previous = "ACCESSIBILITY: DISCONNECTED",
            explicit = null,
            clear = false,
            traceLast = "ACCESSIBILITY: DISCONNECTED",
        )
        assertThat(shown).isNull()
    }

    @Test
    fun paused_keepsPreviousStopReason() {
        val shown = DiagnosticsStopDisplay.resolve(
            mode = AutoPlayController.Mode.PAUSED,
            previous = "ACCESSIBILITY: DISCONNECTED",
            explicit = null,
            clear = false,
            traceLast = null,
        )
        assertThat(shown).isEqualTo("ACCESSIBILITY: DISCONNECTED")
    }

    @Test
    fun clear_forcesNullEvenWhenTraceHasStop() {
        val shown = DiagnosticsStopDisplay.resolve(
            mode = AutoPlayController.Mode.IDLE,
            previous = "ACCESSIBILITY: DISCONNECTED",
            explicit = null,
            clear = true,
            traceLast = "ACCESSIBILITY: DISCONNECTED",
        )
        assertThat(shown).isNull()
    }

    @Test
    fun explicitStop_winsWhenNotClearing() {
        val shown = DiagnosticsStopDisplay.resolve(
            mode = AutoPlayController.Mode.PAUSED,
            previous = "old",
            explicit = "szünet (biztonság): fail",
            clear = false,
            traceLast = "trace",
        )
        assertThat(shown).isEqualTo("szünet (biztonság): fail")
    }

    @Test
    fun autoPlayTrace_clearLastStop_doesNotWipeRing() {
        AutoPlayTrace.log("MODE RUNNING", "test")
        AutoPlayTrace.markStop("ACCESSIBILITY: DISCONNECTED")
        assertThat(AutoPlayTrace.lastStopReason).isEqualTo("ACCESSIBILITY: DISCONNECTED")
        AutoPlayTrace.clearLastStop()
        assertThat(AutoPlayTrace.lastStopReason).isNull()
        assertThat(AutoPlayTrace.recentLines()).isNotEmpty()
    }

    @Test
    fun successfulStart_clearsLastStopReason() {
        AutoPlayTrace.markStop("ACCESSIBILITY: DISCONNECTED")
        val ctrl = AutoPlayController()
        assertThat(
            ctrl.onStartRequested(
                a11yConnected = true,
                captureReady = true,
                overlayReady = true,
            ),
        ).isTrue()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.RUNNING)
        assertThat(AutoPlayTrace.lastStopReason).isNull()
    }
}
