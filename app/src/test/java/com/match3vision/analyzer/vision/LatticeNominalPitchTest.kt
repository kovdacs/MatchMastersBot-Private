package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Phone 0.24.7.6 held on ROI height 1099 (pitch 157) and passed on height
 * 1050 (pitch 150). 150 px is used only when that pitch is legal for the
 * frame and the gutter score still clears the existing lattice bar.
 */
class LatticeNominalPitchTest {

    @Test
    fun gatesStayClosed() {
        assertThat(VisionThresholds.MIN_GRID_CONFIDENCE).isEqualTo(0.98f)
        assertThat(VisionThresholds.MIN_BOARD_CONFIDENCE).isEqualTo(0.95f)
        assertThat(VisionThresholds.MAX_UNKNOWN_COUNT).isEqualTo(1)
        assertThat(BoardFinder.LATTICE_MIN_SCORE).isEqualTo(5f)
    }

    @Test
    fun phonePitch157_deviates_and150IsLegalOn1080() {
        val badHeight = 2265 - 1166
        val goodHeight = 2230 - 1180
        assertThat(badHeight / 7).isEqualTo(157)
        assertThat(goodHeight).isEqualTo(7 * BoardFinder.NOMINAL_LATTICE_PITCH)
        assertThat(BoardFinder.nominalPitchDeviates(157)).isTrue()
        assertThat(BoardFinder.nominalPitchDeviates(150)).isFalse()
        assertThat(BoardFinder.nominalPitchDeviates(149)).isFalse()
        assertThat(BoardFinder.nominalPitchDeviates(154)).isFalse()
        assertThat(BoardFinder.nominalPitchLegal(1080, 150)).isTrue()
        assertThat(BoardFinder.nominalPitchLegal(1080, 157)).isTrue()
        assertThat(BoardFinder.nominalPitchLegal(640, 150)).isFalse()
    }

    @Test
    fun knownHoldFrames_stayHold_andGoldenStaysOnProjection() {
        val pipeline = VisionPipeline()
        val golden = RealFrameLoader.loadFromResource("real_frames/pvp_board.jpg")
            ?: error("missing pvp")
        val pass = pipeline.analyze(golden.pixels, golden.width, golden.height)
        assertThat(pass.validation.isPass).isTrue()
        assertThat(pass.diagnostics["latticeRoiUsed"]).isEqualTo("no")
        assertThat(pass.diagnostics["latticeNominal150"] ?: "none").isEqualTo("none")
        assertThat(pass.gridConfidence).isWithin(5e-4f).of(0.9872f)

        val holds = listOf(
            "real_frames/pvp_board_activate_fx.jpg",
            "real_frames/pvp_board_showdown_overlay.jpg",
            "real_frames/device_test0_0.24.5/native_screenshot_running_1.jpg",
            "real_frames/device_test0_0.24.5/native_screenshot_running_2.jpg",
            "real_frames/device_test0_0.24.6.1/shot_after_stop.jpg",
        )
        holds.forEach { path ->
            val frame = RealFrameLoader.loadFromResource(path) ?: error("missing $path")
            val result = pipeline.analyze(frame.pixels, frame.width, frame.height)
            assertThat(result.validation.isPass).isFalse()
        }
    }
}
