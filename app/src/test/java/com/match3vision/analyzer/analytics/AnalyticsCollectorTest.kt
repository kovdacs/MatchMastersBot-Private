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

    @Test
    fun unknownRate_usesTheExaminedCellCount() {
        val a = AnalyticsCollector()
        a.onVision(gatePass = true, unknownCount = 2, cells = 10)
        a.onVision(gatePass = true, unknownCount = 0, cells = 20)
        assertThat(a.snapshot().unknownRate).isEqualTo(2f / 30f)

        val rejected = AnalyticsCollector()
        try {
            rejected.onVision(gatePass = true, unknownCount = 2, cells = 0)
            throw AssertionError("cells=0 should be rejected")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("positive")
        }
        try {
            rejected.onVision(gatePass = true, unknownCount = 11, cells = 10)
            throw AssertionError("unknownCount past the cell count should be rejected")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("unknownCount")
        }
    }
}
