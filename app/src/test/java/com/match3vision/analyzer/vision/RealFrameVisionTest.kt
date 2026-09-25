package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import org.junit.Assume
import org.junit.Test

/**
 * REAL_FRAME / REAL_FIXTURE harness.
 *
 * Loads `real_frames/pvp_board.jpg` when present and runs [VisionPipeline],
 * dumping numeric diagnostics on assertion failure.
 *
 * When the real Match Masters capture is absent (current state: REAL_FRAME_MISSING),
 * tests are skipped via [Assume] so CI stays green.
 *
 * Photorealistic-synthetic robustness lives in other *Robustness* / *Realistic* tests
 * and must not be claimed as real Match Masters frames.
 */
class RealFrameVisionTest {

    private val pipeline = VisionPipeline()

    @Test
    fun realFrame_pvpBoard_pipelineDiagnostics_whenPresent() {
        Assume.assumeTrue(
            "REAL_FRAME_MISSING: classpath resource ${RealFrameLoader.PVP_BOARD_RESOURCE} not present",
            RealFrameLoader.resourceExists(),
        )
        val frame = RealFrameLoader.loadFromResource()
            ?: error("resourceExists true but load failed")
        val result = pipeline.analyze(
            frame.pixels,
            frame.width,
            frame.height,
            ContentRoi.full(frame.width, frame.height),
        )

        // Soft structural asserts; dump full diagnostics on any hard failure
        VisionDiagnostics.assertOrDump(
            result,
            result.board.cells.size == 7 && result.board.cells[0].size == 7,
            "expected 7x7 board from REAL_FRAME",
        )
        VisionDiagnostics.assertOrDump(
            result,
            result.grid.xBoundaries.size == 8 && result.grid.yBoundaries.size == 8,
            "expected 8x8 boundaries from REAL_FRAME",
        )

        // Document numeric gate fields (may PASS or HOLD on a real capture)
        assertThat(result.gridConfidence).isAtLeast(0f)
        assertThat(result.boardConfidence).isAtLeast(0f)
        assertThat(result.unknownCount).isAtLeast(0)

        // Always emit a one-line summary for CI logs when the fixture is present
        println(
            "REAL_FRAME ok source=${frame.source} " +
                "gridConf=${"%.4f".format(result.gridConfidence)} " +
                "boardConf=${"%.4f".format(result.boardConfidence)} " +
                "unknowns=${result.unknownCount} " +
                "gate=${result.validation}",
        )
    }

    @Test
    fun realFrame_harness_reportsMissingClearly() {
        // Dedicated marker test: documents absence without failing CI.
        if (!RealFrameLoader.resourceExists()) {
            println(
                "REAL_FRAME_MISSING: ${RealFrameLoader.PVP_BOARD_RESOURCE} not in test resources. " +
                    "Parity reference remains REFERENCE_PENDING. " +
                    "Harness RealFrameLoader + Assume skip path is wired.",
            )
            Assume.assumeTrue("REAL_FRAME_MISSING (documented)", false)
        }
        assertThat(RealFrameLoader.resourceExists()).isTrue()
    }
}
