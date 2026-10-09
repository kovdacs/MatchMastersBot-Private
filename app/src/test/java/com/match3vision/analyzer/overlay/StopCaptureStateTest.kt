package com.match3vision.analyzer.overlay

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.input.LoopFailure
import kotlinx.coroutines.CancellationException
import org.junit.Test

/** STOP must not log coroutine cancellation as HIBA, and must drop a stale FRESH frame. */
class StopCaptureStateTest {

    @Test
    fun cancellation_isNotHiba_andStopClearsFreshCapture() {
        val cancelled = CancellationException("StandaloneCoroutine was cancelled")
        assertThat(LoopFailure.reasonOrNull(cancelled)).isNull()
        assertThat(LoopFailure.reasonOrNull(IllegalStateException("boom"))).contains("HIBA")

        AutoPlaySession.controller.resetForNewSession()
        AutoPlaySession.publish(captureReady = true)
        AutoPlaySession.updateDiagnostics(
            phase = "FUT",
            captureStatus = "ON",
            frameFreshness = "FRESH",
            frameAgeMs = 325L,
            frameWidth = 1080,
            frameHeight = 2400,
            frameTimestampMs = 1_791_480_646_333L,
            hasFrameFlag = true,
            frameReceived = true,
            stopReason = "HIBA: StandaloneCoroutine was cancelled",
        )
        AutoPlaySession.controller.onBubbleStop("bubble STOP")
        AutoPlaySession.publishStoppedCapture()
        val text = AutoPlaySession.ui.value.diagnostics.bubbleLines(compact = false)
        assertThat(text).doesNotContain("HIBA")
        assertThat(text).doesNotContain("StandaloneCoroutine")
        assertThat(text).contains("CAPTURE: OFF")
        assertThat(text).doesNotContain("FRESH")
        assertThat(AutoPlaySession.ui.value.captureReady).isFalse()
        assertThat(AutoPlaySession.ui.value.diagnostics.captureStatus).isEqualTo("OFF")
        assertThat(AutoPlaySession.ui.value.diagnostics.frameFreshness).isEqualTo("NONE")
    }

    @Test
    fun analysisOnlyStatus_doesNotSayAutoRunning() {
        AutoPlaySession.controller.resetForNewSession()
        assertThat(AutoPlaySession.controller.onDiagnosticStart()).isTrue()
        AutoPlaySession.updateDiagnostics(
            phase = "FUT",
            captureStatus = "ON",
            frameFreshness = "FRESH",
        )
        val text = AutoPlaySession.ui.value.diagnostics.bubbleLines(compact = true)
        assertThat(text).contains(OverlayPlacement.ANALYSIS_ONLY_CHIP_HU)
        assertThat(text).doesNotContain("AUTO: RUNNING")
        assertThat(OverlayPlacement.collapsedChipCaption(true))
            .isEqualTo("ELEMZÉS – NINCS ÉRINTÉS")
        AutoPlaySession.controller.onBubbleStop("bubble STOP")
    }
}
