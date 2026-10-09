package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * 0.24.7.6 replaces projection peaks with ROI/7 on both axes when
 * latticeRoiUsed=yes. Gates stay 0.98 / 0.95 / unk≤1. Frames that were
 * HOLD before this change must stay HOLD. A PASS on any frame must still
 * clear those gates. The PvP golden does not use the lattice, so its
 * confidence stays on the projection peaks.
 */
class LatticeUniformFalsePassTest {

    private val pipeline = VisionPipeline()

    @Test
    fun gatesStayClosed() {
        assertThat(VisionThresholds.MIN_GRID_CONFIDENCE).isEqualTo(0.98f)
        assertThat(VisionThresholds.MIN_BOARD_CONFIDENCE).isEqualTo(0.95f)
        assertThat(VisionThresholds.MAX_UNKNOWN_COUNT).isEqualTo(1)
    }

    @Test
    fun knownFrames_latticeUniform_doesNotCreateAFalsePass() {
        val golden = analyze("real_frames/pvp_board.jpg")
        assertPass(golden)
        assertThat(golden.result.diagnostics["latticeRoiUsed"]).isEqualTo("no")
        assertThat(golden.result.diagnostics["playfieldSnap"]).isEqualTo("separator_square")
        assertThat(golden.result.diagnostics["uniformSnap"]).contains("x:kept")
        assertThat(golden.result.diagnostics["uniformSnap"]).contains("y:kept")
        assertThat(golden.result.gridConfidence).isWithin(5e-4f).of(0.9872f)

        val midVolume = analyze("real_frames/pvp_board_mid_volume.jpg")
        assertPass(midVolume)
        assertThat(midVolume.result.diagnostics["latticeRoiUsed"]).isEqualTo("no")

        val crop = analyze("real_frames/debug/pvp_board_roi_crop.jpg")
        assertPass(crop)

        val mustHold = listOf(
            "real_frames/pvp_board_activate_fx.jpg",
            "real_frames/pvp_board_showdown_overlay.jpg",
            "real_frames/device_test0_0.24.5/native_screenshot_paused_hiba.jpg",
            "real_frames/device_test0_0.24.5/native_screenshot_running_1.jpg",
            "real_frames/device_test0_0.24.5/native_screenshot_running_2.jpg",
            "real_frames/device_test0_0.24.6.1/shot_after_stop.jpg",
        )
        mustHold.forEach { path ->
            val shot = analyze(path)
            assertWithMessage(shot.line)
                .that(shot.result.validation.isPass)
                .isFalse()
        }

        val ingame = analyze("real_frames/device_test0_0.24.6.1/shot_ingame_chip.jpg")
        assertThat(ingame.result.diagnostics["latticeRoiUsed"]).isEqualTo("yes")
        assertThat(ingame.result.diagnostics["uniformSnap"]).contains("x:period=")
        assertThat(ingame.result.diagnostics["uniformSnap"]).contains("y:period=")
        assertThat(ingame.result.diagnostics["uniformSnap"]).contains("origin=0")
        if (ingame.result.validation.isPass) {
            assertPass(ingame)
        }

        val overlay = analyze("real_frames/debug/pvp_board_roi_overlay.jpg")
        if (overlay.result.validation.isPass) {
            assertPass(overlay)
        }
    }

    private fun assertPass(shot: Shot) {
        assertWithMessage(shot.line).that(shot.result.validation.isPass).isTrue()
        assertThat(shot.result.gridConfidence).isAtLeast(VisionThresholds.MIN_GRID_CONFIDENCE)
        assertThat(shot.result.boardConfidence).isAtLeast(VisionThresholds.MIN_BOARD_CONFIDENCE)
        assertThat(shot.result.unknownCount).isAtMost(VisionThresholds.MAX_UNKNOWN_COUNT)
    }

    private fun analyze(path: String): Shot {
        val frame = RealFrameLoader.loadFromResource(path) ?: error("missing $path")
        val result = pipeline.analyze(frame.pixels, frame.width, frame.height)
        val roi = result.grid.boardRoi
        val line = "$path gate=${if (result.validation.isPass) "PASS" else "HOLD"} " +
            "lattice=${result.diagnostics["latticeRoiUsed"]} " +
            "snap=${result.diagnostics["uniformSnap"]} " +
            "roi=LTRB(${roi.left},${roi.top},${roi.right},${roi.bottom}) " +
            "grid=${result.gridConfidence} board=${result.boardConfidence} " +
            "unk=${result.unknownCount} validation=${result.validation}"
        println("LATTICE_REPLAY $line")
        return Shot(result, line)
    }

    private data class Shot(val result: VisionResult, val line: String)
}
