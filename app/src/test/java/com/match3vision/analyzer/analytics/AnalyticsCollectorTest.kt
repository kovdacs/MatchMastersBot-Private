package com.match3vision.analyzer.analytics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AnalyticsCollectorTest {
    @Test fun rates_and_export() {
        val a = AnalyticsCollector()
        a.onVision(gatePass = true, unknownCount = 0)
        a.onVision(gatePass = false, unknownCount = 5)
        a.onMoves(evaluated = 10, sims = 10)
        val s = a.snapshot()
        assertThat(s.framesAnalyzed).isEqualTo(2)
        assertThat(s.gateFailRate).isEqualTo(0.5f)
        assertThat(a.exportJson()).contains("frames")
    }
}
