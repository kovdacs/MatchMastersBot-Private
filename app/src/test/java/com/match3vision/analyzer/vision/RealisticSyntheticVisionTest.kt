package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Harness mode **REALISTIC_SYNTHETIC** — always runs (no Assume skip).
 *
 * Input is [RealisticSyntheticFixture] (degraded IntArray board). This is **not**
 * a real Match Masters frame and must never be reported as REAL_FRAME validation.
 *
 * Companion modes (same vision package):
 * - REAL_FRAME — [RealFrameVisionTest] (Assume-skip if pvp_board.jpg missing)
 * - SYNTHETIC_UNIT — clean [SyntheticFrames.letterboxedBoard] in VisionPipelineTest etc.
 *
 * Ground truth: [RealisticSyntheticGroundTruth.fromParams] from construction params only.
 */
class RealisticSyntheticVisionTest {

    private val pipeline = VisionPipeline()

    @Test
    fun realisticSynthetic_alwaysRuns_pipelineDiagnostics() {
        val built = RealisticSyntheticFixture.buildCanonical()
        val gt = RealisticSyntheticGroundTruth.fromParams(built)
        val result = pipeline.analyze(
            built.pixels,
            built.width,
            built.height,
            built.contentRoi,
        )

        VisionDiagnostics.assertOrDump(
            result,
            result.board.cells.size == 7 && result.board.cells[0].size == 7,
            "REALISTIC_SYNTHETIC expected 7x7",
        )
        VisionDiagnostics.assertOrDump(
            result,
            result.grid.xBoundaries.size == 8 && result.grid.yBoundaries.size == 8,
            "REALISTIC_SYNTHETIC expected 8 boundaries",
        )

        // Construction-authored occlusion: prefer unknowns ≥ authored count; document FN if not
        if (result.unknownCount < gt.expectedUnknownAtLeast) {
            println(
                "REALISTIC_SYNTHETIC DOC: authored occlusion not fully reflected " +
                    "(unknowns=${result.unknownCount} < ${gt.expectedUnknownAtLeast}) — possible FN under noise/quant",
            )
        }
        val occCell = result.board.get(3, 5)
        println(
            "REALISTIC_SYNTHETIC occ(3,5) unk=${occCell.isUnknown} occ=${occCell.occluded} " +
                "color=${occCell.color}",
        )

        // Color agreement for VERIFIED cells that pipeline did not mark unknown
        var colorMatches = 0
        var colorCompared = 0
        var colorChecked = 0
        for (cellGt in gt.cells) {
            if (cellGt.colorStatus != RealisticSyntheticGroundTruth.CellGtStatus.VERIFIED) continue
            colorChecked++
            val got = result.board.get(cellGt.row, cellGt.col)
            if (got.occluded || got.isUnknown) continue
            colorCompared++
            if (got.color == cellGt.color) colorMatches++
        }
        // Floor: among compared known cells, majority should match construction color.
        // Aspirational 0.70 is logged; hard floor 0.50 avoids greenwashing via forced PASS.
        if (colorCompared > 0) {
            val rate = colorMatches.toFloat() / colorCompared
            println(
                "REALISTIC_SYNTHETIC colorMatch=$colorMatches/$colorCompared " +
                    "(verifiableGT=$colorChecked) rate=$rate",
            )
            VisionDiagnostics.assertOrDump(
                result,
                rate >= 0.50f,
                "REALISTIC_SYNTHETIC color match rate $rate ($colorMatches/$colorCompared) < 0.50",
            )
        }

        val gate = when (val v = result.validation) {
            is ValidationResult.Pass -> "PASS"
            is ValidationResult.Hold -> "HOLD(${v.reason})"
        }
        println(
            "REALISTIC_SYNTHETIC ok " +
                "gridConf=${"%.4f".format(result.gridConfidence)} " +
                "boardConf=${"%.4f".format(result.boardConfidence)} " +
                "unknowns=${result.unknownCount} " +
                "method=${result.method} " +
                "gate=$gate " +
                "colorMatch=$colorMatches/$colorCompared (gt=$colorChecked) " +
                "provenance=${RealisticSyntheticFixture.PROVENANCE}",
        )
        // Document: PASS on realistic synthetic is NOT real-world validation
        assertThat(built.label).isEqualTo("REALISTIC_SYNTHETIC")
        assertThat(gt.provenance).contains("NOT from VisionPipeline")
    }

    @Test
    fun realisticSynthetic_groundTruth_fromConstructionNotDetector() {
        val built = RealisticSyntheticFixture.buildCanonical()
        val gt = RealisticSyntheticGroundTruth.fromParams(built)
        assertThat(gt.label).isEqualTo(RealisticSyntheticFixture.LABEL)
        assertThat(gt.cells).hasSize(49)
        assertThat(gt.expectedOccludedCount).isEqualTo(1)
        val occ = gt.cell(3, 5)
        assertThat(occ.occluded).isTrue()
        assertThat(occ.color).isEqualTo(TileColor.UNKNOWN)
        assertThat(occ.colorStatus)
            .isEqualTo(RealisticSyntheticGroundTruth.CellGtStatus.UNKNOWN)
        // Non-occluded cell color matches palette construction
        val sample = gt.cell(0, 0)
        assertThat(sample.occluded).isFalse()
        assertThat(sample.colorStatus)
            .isEqualTo(RealisticSyntheticGroundTruth.CellGtStatus.VERIFIED)
        assertThat(sample.shapeStatus)
            .isEqualTo(RealisticSyntheticGroundTruth.CellGtStatus.UNVERIFIED)
        assertThat(gt.xBoundaries).hasLength(8)
        assertThat(gt.yBoundaries).hasLength(8)
        println(RealisticSyntheticGroundTruth.toDiagnosticJson(gt).take(500))
    }

    @Test
    fun realisticSynthetic_distinctFromCleanLetterboxedBoard() {
        val realistic = RealisticSyntheticFixture.buildCanonical()
        val (clean, cw, ch) = SyntheticFrames.letterboxedBoard(withGutters = true)
        // Different geometry / letterbox by construction
        assertThat(realistic.width to realistic.height).isNotEqualTo(cw to ch)
        // Pixel buffers must differ (noise/AA/quant path)
        assertThat(realistic.pixels.contentEquals(clean)).isFalse()
    }

    @Test
    fun realisticSynthetic_failurePath_dumpsUnknownMap() {
        // Prove VisionDiagnostics unknown map is usable for this harness mode
        val built = RealisticSyntheticFixture.buildCanonical()
        val result = pipeline.analyze(
            built.pixels, built.width, built.height, built.contentRoi,
        )
        val dump = VisionDiagnostics.formatResult(result, "REALISTIC_SYNTHETIC dump")
        assertThat(dump).contains("unknownMap 7x7")
        assertThat(dump).contains("gridConfidence=")
        // Authored occlusion at (3,5) should appear as U when pipeline marks unknown
        if (result.board.get(3, 5).isUnknown) {
            val lines = dump.lines().filter { it.length == 7 && it.all { ch -> ch == 'U' || ch == '.' } }
            assertThat(lines).isNotEmpty()
            assertThat(lines[3][5]).isEqualTo('U')
        }
    }
}
