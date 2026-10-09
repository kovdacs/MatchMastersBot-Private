package com.match3vision.analyzer.overlay

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.input.CaptureOverlayTrace
import org.junit.Test

/** A pause tick that has no frame must not publish 0×0 over a measured size. */
class FrameSizeRetentionTest {

    @Test
    fun pausedZero_doesNotEraseMeasuredSize_resumeKeepsIt() {
        CaptureOverlayTrace.clear()
        AutoPlaySession.updateDiagnostics(frameWidth = 1080, frameHeight = 2400, phase = "FUT")
        assertThat(AutoPlaySession.ui.value.diagnostics.frameWidth).isEqualTo(1080)
        assertThat(AutoPlaySession.ui.value.diagnostics.frameHeight).isEqualTo(2400)
        assertThat(CaptureOverlayTrace.frameSizeRetained).isFalse()

        AutoPlaySession.updateDiagnostics(frameWidth = 0, frameHeight = 0, phase = "SZÜNET")
        assertThat(AutoPlaySession.ui.value.diagnostics.frameWidth).isEqualTo(1080)
        assertThat(AutoPlaySession.ui.value.diagnostics.frameHeight).isEqualTo(2400)
        assertThat(AutoPlaySession.ui.value.diagnostics.phase).isEqualTo("SZÜNET")
        assertThat(CaptureOverlayTrace.frameSizeRetained).isTrue()

        AutoPlaySession.updateDiagnostics(phase = "SZÜNET")
        assertThat(CaptureOverlayTrace.frameSizeRetained).isTrue()

        AutoPlaySession.updateDiagnostics(frameWidth = 1080, frameHeight = 2400, phase = "FUT")
        assertThat(AutoPlaySession.ui.value.diagnostics.frameWidth).isEqualTo(1080)
        assertThat(AutoPlaySession.ui.value.diagnostics.frameHeight).isEqualTo(2400)
        assertThat(CaptureOverlayTrace.frameSizeRetained).isFalse()
        assertThat(AutoPlaySession.ui.value.diagnostics.frameWidth).isNotEqualTo(0)
        assertThat(AutoPlaySession.ui.value.diagnostics.frameHeight).isNotEqualTo(0)
    }
}
