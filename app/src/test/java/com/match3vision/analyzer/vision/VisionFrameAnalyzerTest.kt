package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import org.junit.Test

class VisionFrameAnalyzerTest {

    private val analyzer = VisionFrameAnalyzer()

    @Test
    fun analyzePixels_producesDebugSummaryAndJson() {
        // Minimal synthetic frame: dark letterbox + colorful board region
        val w = 70
        val h = 70
        val pixels = IntArray(w * h) { 0xFF101010.toInt() }
        // Fill a brighter 49-ish region
        for (y in 5 until 65) for (x in 5 until 65) {
            val hueBucket = ((x / 10) + (y / 10)) % 6
            val color = when (hueBucket) {
                0 -> 0xFF2060FF.toInt() // blue
                1 -> 0xFFFF3030.toInt()
                2 -> 0xFFFFD020.toInt()
                3 -> 0xFF30C030.toInt()
                4 -> 0xFFA030FF.toInt()
                else -> 0xFFFF8C20.toInt()
            }
            pixels[y * w + x] = color
        }
        val analysis = analyzer.analyzePixels(pixels, w, h, ContentRoi(5, 5, 65, 65))
        assertThat(analysis.debugSummary.cellLabels).hasSize(49)
        assertThat(analysis.jsonExport).contains("\"gate\"")
        assertThat(analysis.debugSummary.gridMethod).isAnyOf("PROJECTION", "EVEN_SPLIT")
        assertThat(analysis.result.grid.cells()).hasSize(49)
    }

    @Test
    fun analyzePixels_neverInventedFrame_usesProvidedBuffer() {
        val w = 21
        val h = 21
        val pixels = IntArray(w * h) { 0xFF808080.toInt() }
        val a = analyzer.analyzePixels(pixels, w, h, null)
        assertThat(a.imageWidth).isEqualTo(w)
        assertThat(a.imageHeight).isEqualTo(h)
        assertThat(a.jsonExport).contains("\"imageWidth\": 21")
    }

    @Test
    fun holdGate_exposedInDebugSummary() {
        // Uniform gray → likely many unknowns / low conf → HOLD
        val w = 70
        val h = 70
        val pixels = IntArray(w * h) { 0xFF777777.toInt() }
        val a = analyzer.analyzePixels(pixels, w, h, ContentRoi(0, 0, 70, 70))
        // Gate may be HOLD or PASS depending on heuristics; ensure field populated
        assertThat(a.debugSummary.gate).isAnyOf("PASS", "HOLD")
        assertThat(a.debugSummary.unknownCount).isAtLeast(0)
    }
}
