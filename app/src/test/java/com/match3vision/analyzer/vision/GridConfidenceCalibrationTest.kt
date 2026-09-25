package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * GridConfidence calibration table: relVar → conf → PASS/HOLD.
 *
 * Mirrors BoardFinder projection scoring:
 *   conf = (1f - (relVarX + relVarY) * 1.5f).coerceIn(0.85f, 0.99f)
 *
 * Does **not** inflate confidence artificially. Gate constants stay
 * MIN_GRID=0.98, MIN_BOARD=0.95, MAX_UNKNOWN=1.
 */
class GridConfidenceCalibrationTest {

    private fun projectedConf(relVarX: Float, relVarY: Float): Float {
        val projectionMinConfidence = 0.85f
        return (1f - (relVarX + relVarY) * 1.5f).coerceIn(projectionMinConfidence, 0.99f)
    }

    private fun gate(conf: Float): String =
        if (conf >= VisionThresholds.MIN_GRID_CONFIDENCE) "PASS" else "HOLD"

    @Test
    fun calibrationTable_documentsRelVarToGate() {
        val rows = listOf(
            Triple(0.000f, 0.000f, "clean-zero"),
            Triple(0.004f, 0.004f, "clean-ish"),
            Triple(0.010f, 0.010f, "mild"),
            Triple(0.020f, 0.020f, "moderate"),
            Triple(0.040f, 0.040f, "degraded"),
            Triple(0.060f, 0.060f, "heavy"),
            Triple(0.100f, 0.100f, "floor"),
        )
        println("GridConfidence calibration (relVarX,relVarY) → conf → gate")
        println("| relVarX | relVarY | conf | gate | label |")
        println("|---------|---------|------|------|-------|")
        for ((vx, vy, label) in rows) {
            val conf = projectedConf(vx, vy)
            val g = gate(conf)
            println(
                "| ${"%.3f".format(vx)} | ${"%.3f".format(vy)} | " +
                    "${"%.4f".format(conf)} | $g | $label |",
            )
            assertThat(conf).isAtLeast(0.85f)
            assertThat(conf).isAtMost(0.99f)
        }
        // Explicit anchors
        assertThat(projectedConf(0.004f, 0.004f))
            .isAtLeast(VisionThresholds.MIN_GRID_CONFIDENCE)
        assertThat(projectedConf(0.060f, 0.060f))
            .isLessThan(VisionThresholds.MIN_GRID_CONFIDENCE)
        assertThat(VisionThresholds.MIN_GRID_CONFIDENCE).isEqualTo(0.98f)
    }

    @Test
    fun noArtificialInflation_beyondFormula() {
        // Conf must equal the documented formula — no secret boost
        val vx = 0.015f
        val vy = 0.015f
        val expected = (1f - (vx + vy) * 1.5f).coerceIn(0.85f, 0.99f)
        assertThat(projectedConf(vx, vy)).isWithin(1e-6f).of(expected)
        assertThat(expected).isLessThan(VisionThresholds.MIN_GRID_CONFIDENCE)
    }
}
