package com.match3vision.analyzer.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AnalysisFrameGateTest {

    @Test
    fun default_foreground_rejectsLive() {
        val gate = AnalysisFrameGate()
        assertThat(gate.analyzerUiForeground).isTrue()
        assertThat(gate.shouldAcceptLiveFrame()).isFalse()
    }

    @Test
    fun background_acceptsLive() {
        val gate = AnalysisFrameGate()
        gate.setAnalyzerUiForeground(false)
        assertThat(gate.shouldAcceptLiveFrame()).isTrue()
        assertThat(gate.statusText()).contains("ÉLŐ")
    }

    @Test
    fun resume_freezesAgain() {
        val gate = AnalysisFrameGate()
        gate.setAnalyzerUiForeground(false)
        assertThat(gate.shouldAcceptLiveFrame()).isTrue()
        gate.setAnalyzerUiForeground(true)
        assertThat(gate.shouldAcceptLiveFrame()).isFalse()
        assertThat(gate.statusText()).contains("FAGYASZTVA")
        assertThat(gate.frozenFrameCount).isAtLeast(1)
    }

    @Test
    fun forceAccept_overridesFreeze() {
        val gate = AnalysisFrameGate()
        gate.setAnalyzerUiForeground(true)
        assertThat(gate.shouldAcceptLiveFrame()).isFalse()
        gate.setForceAcceptLive(true)
        assertThat(gate.shouldAcceptLiveFrame()).isTrue()
        gate.setForceAcceptLive(false)
        assertThat(gate.shouldAcceptLiveFrame()).isFalse()
    }

    @Test
    fun counters_trackAcceptedAndDiscarded() {
        val gate = AnalysisFrameGate()
        gate.onFrameOffered(accepted = false)
        gate.onFrameOffered(accepted = false)
        gate.setAnalyzerUiForeground(false)
        gate.onFrameOffered(accepted = true)
        assertThat(gate.discardedWhileFrozen).isEqualTo(2)
        assertThat(gate.acceptedFrameCount).isEqualTo(1)
    }
}
