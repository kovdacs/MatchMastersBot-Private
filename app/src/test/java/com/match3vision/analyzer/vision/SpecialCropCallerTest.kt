package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import org.junit.Test

/**
 * 131×339 is one cell box from [VisionPipeline.analyzeCell], not a second detector.
 * The clamp still refuses the unguarded index. The audit names the caller.
 */
class SpecialCropCallerTest {

    @Test
    fun pipelineCell_131x339_recordsCaller_andDoesNotThrow() {
        val width = 260
        val height = 470
        val pixels = IntArray(width * height) { 0xFFFF2020.toInt() }
        val pipeline = VisionPipeline(boardFinder = TallCellFinder())
        val result = pipeline.analyze(pixels, width, height)
        assertThat(result).isNotNull()
        val origin = SpecialCropAudit.originText()
        assertThat(origin).contains("VisionPipeline.analyzeCell r=2 c=3 131x339 unguardedIndex=-2")
        assertThat(SpecialCropAudit.rejectedCount()).isGreaterThan(0)
        assertThat(SpecialCropAudit.sizesText()).contains("131x339")
        assertThat(SpecialDetector.unguardedArrowIndex(131, 339)).isEqualTo(-2)
        assertThat(131 * 339).isEqualTo(44409)
    }

    /**
     * Cell (2,3) is 131×339. That is the width and height [PixelMath.crop]
     * returns for [GridGeometry.cellBox].
     */
    private class TallCellFinder : BoardFinder() {
        override fun find(
            pixels: IntArray,
            width: Int,
            height: Int,
            contentRoi: ContentRoi?,
            pinnedBoard: ContentRoi?,
        ): FindResult {
            val xs = floatArrayOf(0f, 20f, 40f, 60f, 191f, 211f, 231f, 251f)
            val ys = floatArrayOf(0f, 20f, 40f, 379f, 399f, 419f, 439f, 459f)
            val grid = GridGeometry(
                xBoundaries = xs,
                yBoundaries = ys,
                method = GridMethod.EVEN_SPLIT,
                confidence = 0.72f,
                boardRoi = ContentRoi(0, 0, 251, 459),
            )
            return FindResult(grid, mutableMapOf("playfieldSnap" to "test"))
        }
    }
}
