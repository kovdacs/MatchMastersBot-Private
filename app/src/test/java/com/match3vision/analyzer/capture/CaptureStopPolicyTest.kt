package com.match3vision.analyzer.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CaptureStopPolicyTest {
    @Test
    fun systemStop_removesForeground_withoutStoppingProjectionAgain() {
        val policy = CaptureStopPolicy()
        var projectionStops = 0
        var foreground = 0
        var stopped = 0
        policy.onSystemStop(
            removeForeground = { foreground += 1 },
            stopSelf = { stopped += 1 },
        )
        assertThat(policy.projectionStopCalls).isEqualTo(0)
        assertThat(policy.foregroundRemoved).isEqualTo(1)
        assertThat(policy.stopSelfCalls).isEqualTo(1)
        assertThat(foreground).isEqualTo(1)
        assertThat(stopped).isEqualTo(1)

        policy.onExplicitStop(
            stopProjection = { projectionStops += 1 },
            removeForeground = { foreground += 1 },
            stopSelf = { stopped += 1 },
        )
        policy.onSystemStop(
            removeForeground = { foreground += 1 },
            stopSelf = { stopped += 1 },
        )
        assertThat(projectionStops).isEqualTo(0)
        assertThat(foreground).isEqualTo(1)
        assertThat(stopped).isEqualTo(1)
    }

    @Test
    fun explicitStop_stopsProjectionOnce_andIgnoresASecondCall() {
        val policy = CaptureStopPolicy()
        var projectionStops = 0
        policy.onExplicitStop(
            stopProjection = { projectionStops += 1 },
            removeForeground = {},
            stopSelf = {},
        )
        policy.onExplicitStop(
            stopProjection = { projectionStops += 1 },
            removeForeground = {},
            stopSelf = {},
        )
        policy.onSystemStop(removeForeground = {}, stopSelf = {})
        assertThat(projectionStops).isEqualTo(1)
        assertThat(policy.projectionStopCalls).isEqualTo(1)
        assertThat(policy.foregroundRemoved).isEqualTo(1)
        assertThat(policy.stopSelfCalls).isEqualTo(1)
    }
}
