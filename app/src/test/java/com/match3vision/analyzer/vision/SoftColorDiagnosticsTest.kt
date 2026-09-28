package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import org.junit.Assume
import org.junit.Test

/**
 * Soft-GT color diagnostics on REAL_FRAME (`pvp_board.jpg`).
 *
 * Prints RGB/HSV/luma clues, per-cell UNKNOWN taxonomy, and a soft-GT
 * confusion matrix. Does **not** loosen PASS/HOLD gates.
 *
 * Baseline (0.23.0 export): soft color ≈ 23/41 (~56%). Vision-stab target:
 * center-weighted color + shape inset must improve that rate.
 */
class SoftColorDiagnosticsTest {

    @Test
    fun realFrame_softColorMismatch_report_andConfusionMatrix() {
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

        // Geometry / ROI sanity (fix geometry before chasing color UNKNOWNs)
        val roi = result.grid.boardRoi
        println(
            "GEOMETRY boardRoi=LTRB(${roi.left},${roi.top},${roi.right},${roi.bottom}) " +
                "dims=${roi.width()}x${roi.height()} method=${result.method} " +
                "gridConf=%.4f boardConf=%.4f unk=%d gate=%s".format(
                    result.gridConfidence,
                    result.boardConfidence,
                    result.unknownCount,
                    if (result.validation.isPass) "PASS" else "HOLD",
                ),
        )
        assertThat(result.method).isEqualTo(GridMethod.PROJECTION)
        assertThat(result.gridConfidence).isAtLeast(VisionThresholds.MIN_GRID_CONFIDENCE)
        assertThat(roi.width()).isAtLeast(900)
        assertThat(roi.height()).isAtLeast(900)
        // Expected primary lock from prior calib (allow tiny jitter)
        assertThat(roi.left).isAtLeast(0)
        assertThat(roi.top).isAtLeast(1000)

        val gt = RealFrameHumanGroundTruth.cells.filter {
            it.colorStatus == RealFrameHumanGroundTruth.CellGtStatus.VERIFIED &&
                it.color != TileColor.UNKNOWN
        }

        val colors = listOf(
            TileColor.R, TileColor.O, TileColor.Y,
            TileColor.G, TileColor.B, TileColor.P, TileColor.UNKNOWN,
        )
        val matrix = LinkedHashMap<TileColor, IntArray>()
        for (c in colors) matrix[c] = IntArray(colors.size)

        var match = 0
        val mismatches = mutableListOf<String>()
        val unkReasons = linkedMapOf<String, Int>()
        for (g in gt) {
            val cell = result.board.get(g.row, g.col)
            val det = if (cell.isUnknown) TileColor.UNKNOWN else cell.color
            val gi = colors.indexOf(g.color)
            val di = colors.indexOf(det).coerceAtLeast(colors.indexOf(TileColor.UNKNOWN))
            matrix[g.color]!![di]++
            if (det == g.color) {
                match++
            } else {
                val diag = report.cells.first { it.row == g.row && it.col == g.col }
                mismatches += ("(%d,%d) GT=%s det=%s conf=%.3f RGB=%s HSV=%s L=%s unk=%s reason=%s".format(
                    g.row, g.col, g.color, det, cell.confidence,
                    diag.meanRgb, diag.meanHsv, diag.meanLuma,
                    cell.isUnknown, diag.unknownReason,
                ))
            }
        }
        for (c in report.cells) {
            if (!c.unknown) continue
            val r = c.unknownReason.ifBlank { "?" }
            unkReasons[r] = (unkReasons[r] ?: 0) + 1
        }

        val total = gt.size
        val rate = if (total == 0) 0.0 else match.toDouble() / total
        println("SOFT_COLOR match=$match/$total rate=%.3f".format(rate))
        println("SOFT_COLOR_CONFUSION (rows=GT, cols=det R O Y G B P UNKNOWN)")
        print("GT\\\\det".padEnd(8))
        for (c in colors) print("%8s".format(c.name))
        println()
        for (gCol in colors.dropLast(1)) {
            print(gCol.name.padEnd(8))
            val row = matrix[gCol]!!
            for (i in colors.indices) print("%8d".format(row[i]))
            println()
        }
        mismatches.take(24).forEach { println("MISMATCH $it") }
        if (unkReasons.isNotEmpty()) {
            println("UNKNOWN_REASON_COUNTS " + unkReasons.entries.joinToString(",") { "${it.key}=${it.value}" })
        }

        println(
            "SOFT_COLOR_HYPOTHESES: " +
                "1) BEFORE: full-cell hue vote polluted by blue playfield → ~55% soft; " +
                "2) AFTER: center-disk RGB/HSV/brightness + circular median; " +
                "3) shape inset avoids false STAR from gutters; " +
                "4) purple mushroom +3 still unmodeled — needs labeled crops; " +
                "5) orange inverted-triangle shape UNVERIFIED (color should still match).",
        )

        // Gates unchanged
        assertThat(VisionThresholds.MIN_GRID_CONFIDENCE).isEqualTo(0.98f)
        assertThat(VisionThresholds.MIN_BOARD_CONFIDENCE).isEqualTo(0.95f)
        assertThat(VisionThresholds.MAX_UNKNOWN_COUNT).isEqualTo(1)
        assertThat(report.cells).hasSize(49)
        assertThat(total).isGreaterThan(0)

        // Structural PASS must hold (no artificial threshold loosen)
        assertThat(result.validation.isPass).isTrue()
        assertThat(result.unknownCount).isAtMost(VisionThresholds.MAX_UNKNOWN_COUNT)

        // Soft color must improve vs documented ~0.55 baseline (not a PASS gate)
        assertThat(rate).isAtLeast(0.75)
        println(
            "SOFT_COLOR_BEFORE_AFTER before≈0.56(23/41) after=%.3f(%d/%d)".format(
                rate, match, total,
            ),
        )
    }

    @Test
    fun unknownReason_taxonomy_coversRequiredCategories() {
        assertThat(
            UnknownReason.diagnose(
                TileColor.UNKNOWN, TileShape.CIRCLE, SpecialType.NONE, occluded = false,
            ),
        ).isEqualTo("color")
        assertThat(
            UnknownReason.diagnose(
                TileColor.R, TileShape.UNKNOWN, SpecialType.NONE, occluded = false,
            ),
        ).isEqualTo("shape")
        assertThat(
            UnknownReason.diagnose(
                TileColor.UNKNOWN, TileShape.UNKNOWN, SpecialType.NONE, occluded = true,
            ),
        ).contains("occlusion")
        assertThat(
            UnknownReason.diagnose(
                TileColor.UNKNOWN, TileShape.UNKNOWN, SpecialType.NONE,
                occluded = false, cellW = 2, cellH = 2,
            ),
        ).contains("geometry/grid")
        assertThat(
            UnknownReason.diagnose(
                TileColor.UNKNOWN, TileShape.UNKNOWN, SpecialType.BOMB, occluded = false,
            ),
        ).contains("special")
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
