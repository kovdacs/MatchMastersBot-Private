package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import org.junit.Assume
import org.junit.Test

/**
 * Harness mode **REAL_FRAME**.
 *
 * Loads `real_frames/pvp_board.jpg` (primary) and optional secondary overlay/FX/volume
 * frames; runs [VisionPipeline] with soft structural asserts + println diagnostics.
 *
 * When the real Match Masters capture is absent, tests Assume-skip so CI stays green.
 *
 * Three harness modes (keep separate):
 * - REAL_FRAME — this class
 * - REALISTIC_SYNTHETIC — [RealisticSyntheticVisionTest] (always runs; not real MM)
 * - SYNTHETIC_UNIT — clean [SyntheticFrames.letterboxedBoard] unit tests
 *
 * Gates unchanged: MIN_GRID=0.98, MIN_BOARD=0.95, MAX_UNKNOWN=1.
 * HOLD / high unknowns on real captures are documented — do not loosen gates.
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

        val boardW = result.grid.boardRoi.width()
        val boardH = result.grid.boardRoi.height()
        val gate = gateLabel(result.validation)
        println(
            "REAL_FRAME ok source=${frame.source} " +
                "frame=${frame.width}x${frame.height} " +
                "boardRoi=${result.grid.boardRoi.left},${result.grid.boardRoi.top}," +
                "${result.grid.boardRoi.right},${result.grid.boardRoi.bottom} " +
                "boardDims=${boardW}x${boardH} " +
                "gridConf=${"%.4f".format(result.gridConfidence)} " +
                "boardConf=${"%.4f".format(result.boardConfidence)} " +
                "unknowns=${result.unknownCount} " +
                "method=${result.method} " +
                "gate=$gate " +
                "caveat=android_screenshot_toolbar_may_clip_bottom_row",
        )
        println(VisionDiagnostics.formatResult(result, "REAL_FRAME primary dump"))
    }

    @Test
    fun realFrame_pvpBoard_softHumanGtCompare_whenPresent() {
        Assume.assumeTrue(
            "REAL_FRAME_MISSING: ${RealFrameLoader.PVP_BOARD_RESOURCE}",
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

        assertThat(RealFrameHumanGroundTruth.cells).hasSize(49)
        assertThat(RealFrameHumanGroundTruth.PROVENANCE).contains("HUMAN_VISUAL")
        assertThat(RealFrameHumanGroundTruth.PROVENANCE).contains("NOT from VisionPipeline")

        // Soft color compare — log only; do not fail CI on low rate (real-world noise/crop)
        val soft = RealFrameHumanGroundTruth.softColorCompare(result.board)
        println(
            "REAL_FRAME softGt colorMatch=${soft.matches}/${soft.compared} " +
                "(verifiableGT=${soft.verifiableGt}) rate=${"%.3f".format(soft.rate)} " +
                "provenance=${RealFrameHumanGroundTruth.PROVENANCE}",
        )

        val mush = result.board.get(4, 1)
        println(
            "REAL_FRAME mushroom(4,1) unk=${mush.isUnknown} occ=${mush.occluded} " +
                "color=${mush.color} shape=${mush.shape} special=${mush.special} " +
                "note=SpecialType_has_no_MUSHROOM",
        )

        // Row 6 GT is UNVERIFIED — document what detector saw without hard color asserts
        val row6 = (0 until 7).joinToString(",") { c ->
            val cell = result.board.get(6, c)
            "${cell.color}/${cell.shape}${if (cell.isUnknown) "/U" else ""}"
        }
        println("REAL_FRAME row6_detector_sees=[$row6] gt=UNVERIFIED_toolbar_crop")
    }

    @Test
    fun realFrame_secondaryFrames_softDiagnostics_whenPresent() {
        var ran = 0
        for ((label, resource) in RealFrameLoader.SECONDARY_RESOURCES) {
            if (!RealFrameLoader.resourceExists(resource)) {
                println("REAL_FRAME_SECONDARY_MISSING label=$label resource=$resource")
                continue
            }
            val frame = RealFrameLoader.loadFromResource(resource)
                ?: continue
            val result = pipeline.analyze(
                frame.pixels,
                frame.width,
                frame.height,
                ContentRoi.full(frame.width, frame.height),
            )
            ran++
            // Soft only: always emit diagnostics; Expect HOLD / high unknowns OK for FX/overlay
            assertThat(result.board.cells.size).isEqualTo(7)
            assertThat(result.board.cells[0].size).isEqualTo(7)
            assertThat(result.gridConfidence).isAtLeast(0f)
            assertThat(result.boardConfidence).isAtLeast(0f)
            println(
                "REAL_FRAME_SECONDARY ok label=$label source=${frame.source} " +
                    "gridConf=${"%.4f".format(result.gridConfidence)} " +
                    "boardConf=${"%.4f".format(result.boardConfidence)} " +
                    "unknowns=${result.unknownCount} " +
                    "method=${result.method} " +
                    "gate=${gateLabel(result.validation)} " +
                    "note=soft_only_HOLD_or_high_unknowns_OK",
            )
        }
        if (ran == 0) {
            println("REAL_FRAME_SECONDARY: no secondary JPEGs present — skipped")
            Assume.assumeTrue("no secondary REAL_FRAME resources", false)
        }
        println("REAL_FRAME_SECONDARY ran=$ran/3")
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
        println("REAL_FRAME_AVAILABLE=YES resource=${RealFrameLoader.PVP_BOARD_RESOURCE}")
    }

    private fun gateLabel(v: ValidationResult): String = when (v) {
        is ValidationResult.Pass -> "PASS"
        is ValidationResult.Hold -> "HOLD(${v.reason})"
    }
}
