package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.overlay.OverlayPlacement
import org.junit.Test

/**
 * A collapsed chip painted in the top-end corner must not become an overlay
 * column run, and it must not move the PvP golden.
 */
class CollapsedChipCompositeTest {

    private val pipeline = VisionPipeline()

    @Test
    fun pvpBoard_collapsedChip_keepsGolden_andIsNotAColumnRun() {
        val frame = RealFrameLoader.loadFromResource()
            ?: error("pvp_board.jpg missing")
        val painted = paintChip(frame.pixels, frame.width, frame.height)
        val mask = OverlayColumnMask.detect(painted, frame.width, frame.height)
        assertThat(mask.isEmpty()).isTrue()
        assertThat(mask.describe()).isEqualTo("none")
        val result = pipeline.analyze(painted, frame.width, frame.height)
        assertThat(result.gridConfidence).isWithin(5e-4f).of(0.9872f)
        assertThat(result.validation.isPass).isTrue()
        assertThat(result.diagnostics["gridRecover"] ?: "none").isEqualTo("none")
        assertThat(result.diagnostics["playfieldSnap"]).isEqualTo("separator_square")
        assertThat(result.diagnostics["latticeRoiUsed"]).isEqualTo("no")
    }

    @Test
    fun pvpBoard_brightLowSaturationChip_keepsGolden_andIsNotAColumnRun() {
        assertThat(OverlayPlacement.ANALYSIS_ONLY_CHIP_HU).isEqualTo("ELEMZÉS – NINCS ÉRINTÉS")
        assertThat(OverlayPlacement.collapsedChipCaption(true))
            .isEqualTo(OverlayPlacement.ANALYSIS_ONLY_CHIP_HU)
        assertThat(OverlayPlacement.collapsedChipCaption(false)).isEmpty()
        val frame = RealFrameLoader.loadFromResource()
            ?: error("pvp_board.jpg missing")
        val bright = (0xFF shl 24) or (250 shl 16) or (248 shl 8) or 240
        val painted = paintChip(frame.pixels, frame.width, frame.height, bright)
        val mask = OverlayColumnMask.detect(painted, frame.width, frame.height)
        assertThat(mask.isEmpty()).isTrue()
        assertThat(mask.describe()).isEqualTo("none")
        val result = pipeline.analyze(painted, frame.width, frame.height)
        assertThat(result.gridConfidence).isWithin(5e-4f).of(0.9872f)
        assertThat(result.validation.isPass).isTrue()
        assertThat(result.diagnostics["gridRecover"] ?: "none").isEqualTo("none")
        assertThat(result.diagnostics["playfieldSnap"]).isEqualTo("separator_square")
        assertThat(result.diagnostics["overlayColumns"]).isEqualTo("none")
    }

    @Test
    fun deviceTest0_collapsedChip_doesNotChangeTheBubbleMask() {
        for (resource in listOf(
            "real_frames/device_test0_0.24.5/native_screenshot_running_1.jpg",
            "real_frames/device_test0_0.24.5/native_screenshot_running_2.jpg",
        )) {
            val frame = RealFrameLoader.loadFromResource(resource)
                ?: error("missing $resource")
            val before = OverlayColumnMask.detect(frame.pixels, frame.width, frame.height)
            val painted = paintChip(frame.pixels, frame.width, frame.height)
            val after = OverlayColumnMask.detect(painted, frame.width, frame.height)
            assertThat(after.describe()).isEqualTo(before.describe())
            assertThat(after.isEmpty()).isFalse()
            val result = pipeline.analyze(painted, frame.width, frame.height)
            val roi = result.grid.boardRoi
            assertThat(roi.top).isAtLeast(1175)
            assertThat(roi.top).isAtMost(1205)
            assertThat(roi.bottom).isAtLeast(2225)
            assertThat(roi.bottom).isAtMost(2255)
            assertThat(result.validation.isPass).isFalse()
            assertThat(result.diagnostics["playfieldSnap"]).isEqualTo("gutter_lattice")
            assertThat(result.diagnostics["latticeRoiUsed"]).isEqualTo("yes")
        }
    }

    private fun paintChip(
        pixels: IntArray,
        width: Int,
        height: Int,
        color: Int = (0xFF shl 24) or (236 shl 16) or (236 shl 8) or 236,
    ): IntArray {
        val copy = pixels.copyOf()
        val chip = OverlayPlacement.collapsedChipPx(width, height, 2.75f)
        for (y in chip.top until chip.bottom) {
            val row = y * width
            for (x in chip.left until chip.right) {
                copy[row + x] = color
            }
        }
        return copy
    }
}
