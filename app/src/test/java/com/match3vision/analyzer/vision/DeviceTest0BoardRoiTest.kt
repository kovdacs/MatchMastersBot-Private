package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import org.junit.Test

/**
 * Real-device Test 0 (1080×2400, rotation 0). The native screenshots still
 * contain the expanded bubble over the left columns, so this does not expect
 * a vision PASS and does not invent cell labels.
 *
 * Manual board was about LTRB(0,1190,1080,2240). The gutter lattice on these
 * JPEGs is the checked measurement (±15 px).
 */
class DeviceTest0BoardRoiTest {

    private val pipeline = VisionPipeline()

    @Test
    fun thresholds_stayAtTheDocumentedGates() {
        assertThat(VisionThresholds.MIN_GRID_CONFIDENCE).isEqualTo(0.98f)
        assertThat(VisionThresholds.MIN_BOARD_CONFIDENCE).isEqualTo(0.95f)
        assertThat(VisionThresholds.MAX_UNKNOWN_COUNT).isEqualTo(1)
    }

    @Test
    fun running1_boardRoiCoversTheGems_andStaysHold() {
        assertDeviceRoi("real_frames/device_test0_0.24.5/native_screenshot_running_1.jpg")
    }

    @Test
    fun running2_boardRoiCoversTheGems_andStaysHold() {
        assertDeviceRoi("real_frames/device_test0_0.24.5/native_screenshot_running_2.jpg")
    }

    @Test
    fun pvpGolden_maskEmpty_andConfidenceUnchanged() {
        val frame = RealFrameLoader.loadFromResource()
            ?: error("pvp_board.jpg missing")
        val mask = OverlayColumnMask.detect(frame.pixels, frame.width, frame.height)
        assertThat(mask.isEmpty()).isTrue()
        assertThat(mask.describe()).isEqualTo("none")
        val result = pipeline.analyze(
            frame.pixels,
            frame.width,
            frame.height,
            ContentRoi.full(frame.width, frame.height),
        )
        assertThat(result.gridConfidence).isWithin(5e-4f).of(0.9872f)
        assertThat(result.diagnostics["gridRecover"] ?: "none").isEqualTo("none")
        assertThat(result.diagnostics["playfieldSnap"]).isNotEqualTo("gutter_lattice")
        assertThat(result.validation.isPass).isTrue()
    }

    private fun assertDeviceRoi(resource: String) {
        val frame = RealFrameLoader.loadFromResource(resource)
            ?: error("missing $resource")
        assertThat(frame.width).isEqualTo(1080)
        assertThat(frame.height).isEqualTo(2400)
        val mask = OverlayColumnMask.detect(frame.pixels, frame.width, frame.height)
        assertThat(mask.isEmpty()).isFalse()
        val result = pipeline.analyze(frame.pixels, frame.width, frame.height)
        val roi = result.grid.boardRoi
        println(
            "DEVICE_ROI $resource snap=${result.diagnostics["playfieldSnap"]} " +
                "lattice=${result.diagnostics["lattice"]} overlay=${mask.describe()} " +
                "roi=LTRB(${roi.left},${roi.top},${roi.right},${roi.bottom}) " +
                "grid=${result.gridConfidence} board=${result.boardConfidence} " +
                "unk=${result.unknownCount} validation=${result.validation} " +
                "y=${result.grid.yBoundaries.joinToString(",") { it.toInt().toString() }}",
        )
        val labels = buildString {
            for (r in 0 until 7) {
                for (c in 0 until 7) {
                    val cell = result.board.cells[r][c]
                    append(if (cell.isUnknown) "UNK" else cell.color.name)
                    if (c < 6) append(' ')
                }
                append('\n')
            }
        }
        println(labels)
        assertThat(roi.left).isAtMost(15)
        assertThat(roi.right).isAtLeast(1065)
        assertThat(roi.top).isAtLeast(1175)
        assertThat(roi.top).isAtMost(1205)
        assertThat(roi.bottom).isAtLeast(2225)
        assertThat(roi.bottom).isAtMost(2255)
        assertThat(result.grid.yBoundaries[0]).isLessThan(1360f)
        assertThat(result.grid.yBoundaries[7]).isLessThan(2300f)
        assertThat(result.unknownCount).isGreaterThan(1)
        assertThat(result.validation.isPass).isFalse()
        assertThat(result.diagnostics["playfieldSnap"]).isEqualTo("gutter_lattice")
    }
}
