package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import org.junit.Assume
import org.junit.Test

/**
 * Investigates soft GT color ~55% on REAL_FRAME without loosening gates.
 * Prints concrete RGB/HSV/luma clues for mismatched VERIFIED cells.
 */
class SoftColorDiagnosticsTest {

    @Test
    fun realFrame_softColorMismatch_report() {
        Assume.assumeTrue(
            "REAL_FRAME_MISSING",
            RealFrameLoader.resourceExists(),
        )
        val loaded = RealFrameLoader.loadFromResource()
            ?: error("load failed")
        val pipeline = VisionPipeline()
        val contentRoi = ContentRoi.full(loaded.width, loaded.height)
        val result = pipeline.analyze(loaded.pixels, loaded.width, loaded.height, contentRoi)
        val report = LiveCellDiagnostics.fromVision(
            result = result,
            frameWidth = loaded.width,
            frameHeight = loaded.height,
            contentRoi = contentRoi,
            cellPixels = { r, c ->
                val box = result.grid.cellBox(r, c)
                extractCell(loaded.pixels, loaded.width, loaded.height, box)
            },
        )
        println(report.format())

        val gt = RealFrameHumanGroundTruth.cells.filter {
            it.colorStatus == RealFrameHumanGroundTruth.CellGtStatus.VERIFIED &&
                it.color != TileColor.UNKNOWN
        }
        var match = 0
        val mismatches = mutableListOf<String>()
        for (g in gt) {
            val cell = result.board.get(g.row, g.col)
            if (cell.color == g.color) {
                match++
            } else {
                val diag = report.cells.first { it.row == g.row && it.col == g.col }
                mismatches += ("(%d,%d) GT=%s det=%s conf=%.3f RGB=%s HSV=%s L=%s unk=%s reason=%s".format(
                    g.row, g.col, g.color, cell.color, cell.confidence,
                    diag.meanRgb, diag.meanHsv, diag.meanLuma,
                    cell.isUnknown, diag.unknownReason,
                ))
            }
        }
        val total = gt.size
        val rate = if (total == 0) 0.0 else match.toDouble() / total
        println("SOFT_COLOR match=$match/$total rate=%.3f".format(rate))
        mismatches.take(24).forEach { println("MISMATCH $it") }

        // Hypotheses log (evidence for docs — do not change thresholds here)
        println(
            "SOFT_COLOR_HYPOTHESES: " +
                "1) center-vs-area sampling / gem highlight+shadow skew HSV vote; " +
                "2) AA / JPEG chroma bleed across gutters; " +
                "3) orange inverted-triangle mapped as HEX by shape but hue near Y/O boundary; " +
                "4) purple mushroom + special overlay not modeled; " +
                "5) background playfield bleed at cell edges when inset too small.",
        )

        assertThat(VisionThresholds.MIN_GRID_CONFIDENCE).isEqualTo(0.98f)
        assertThat(VisionThresholds.MIN_BOARD_CONFIDENCE).isEqualTo(0.95f)
        assertThat(VisionThresholds.MAX_UNKNOWN_COUNT).isEqualTo(1)
        assertThat(report.cells).hasSize(49)
        assertThat(total).isGreaterThan(0)
    }

    private fun extractCell(
        pixels: IntArray,
        width: Int,
        height: Int,
        box: CellGeometry,
    ): IntArray {
        val left = box.left.toInt().coerceIn(0, width - 1)
        val top = box.top.toInt().coerceIn(0, height - 1)
        val right = box.right.toInt().coerceIn(left + 1, width)
        val bottom = box.bottom.toInt().coerceIn(top + 1, height)
        val w = right - left
        val h = bottom - top
        val out = IntArray(w * h)
        var i = 0
        for (y in top until bottom) {
            for (x in left until right) {
                out[i++] = pixels[y * width + x]
            }
        }
        return out
    }
}
