package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Settings flag alone ≠ connected. Runtime connected is required for
 * RUNNING / input-ready.
 */
class StartupReadinessGateTest {

    @Test
    fun settingsEnabledAlone_blocksRunningAndInputReady() {
        val d = StartupReadinessGate.evaluate(
            runtimeConnected = false,
            settingsEnabled = true,
            captureReady = true,
            overlayReady = true,
            inputSwitchEnabled = true,
        )
        assertThat(d.a11yStatus).isEqualTo(StartupReadinessGate.A11yStatus.DISCONNECTED)
        assertThat(d.canEnterRunning).isFalse()
        assertThat(d.inputReady).isFalse()
        assertThat(d.blockReason).contains("ACCESSIBILITY: DISCONNECTED")
        assertThat(d.blockReason).contains("settings on")
    }

    @Test
    fun runtimeDisconnected_noSettings_blocksClearly() {
        val d = StartupReadinessGate.evaluate(
            runtimeConnected = false,
            settingsEnabled = false,
        )
        assertThat(d.canEnterRunning).isFalse()
        assertThat(d.inputReady).isFalse()
        assertThat(d.blockReason).isEqualTo("ACCESSIBILITY: DISCONNECTED")
    }

    @Test
    fun runtimeConnected_allowsRunning_inputReadyOnlyWhenSwitchOn() {
        val off = StartupReadinessGate.evaluate(
            runtimeConnected = true,
            captureReady = true,
            overlayReady = true,
            inputSwitchEnabled = false,
        )
        assertThat(off.a11yStatus).isEqualTo(StartupReadinessGate.A11yStatus.CONNECTED)
        assertThat(off.canEnterRunning).isTrue()
        assertThat(off.inputReady).isFalse()
        assertThat(off.blockReason).isNull()

        val on = StartupReadinessGate.evaluate(
            runtimeConnected = true,
            captureReady = true,
            overlayReady = true,
            inputSwitchEnabled = true,
        )
        assertThat(on.canEnterRunning).isTrue()
        assertThat(on.inputReady).isTrue()
    }

    @Test
    fun captureOff_blocksEvenWhenConnected() {
        val d = StartupReadinessGate.evaluate(
            runtimeConnected = true,
            captureReady = false,
            overlayReady = true,
        )
        assertThat(d.canEnterRunning).isFalse()
        assertThat(d.blockReason).isEqualTo("CAPTURE: OFF")
    }

    @Test
    fun autoPlayController_rejectsStartWhenA11yDisconnected() {
        val ctrl = AutoPlayController()
        assertThat(
            ctrl.onStartRequested(
                a11yConnected = false,
                settingsEnabled = true,
                captureReady = true,
                overlayReady = true,
            ),
        ).isFalse()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.IDLE)
        assertThat(ctrl.enableSwitch().isEnabled()).isFalse()
        assertThat(ctrl.lastReason).contains("ACCESSIBILITY: DISCONNECTED")
    }

    @Test
    fun autoPlayController_allowsStartWhenA11yConnected() {
        val ctrl = AutoPlayController()
        PlayPermit.allowContinuousStart()
        assertThat(
            ctrl.onStartRequested(
                a11yConnected = true,
                captureReady = true,
                overlayReady = true,
            ),
        ).isTrue()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.RUNNING)
        assertThat(ctrl.enableSwitch().isEnabled()).isTrue()
    }
}
