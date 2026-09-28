package com.match3vision.analyzer.vision

/**
 * Human-authored ground truth for primary REAL_FRAME `pvp_board.jpg`.
 *
 * **Provenance:** HUMAN_VISUAL — from looking at the JPEG (early PvP, Time Left 103,
 * scores 0–0, mushroom +3 at 0-based (4,1)). **Never** from VisionPipeline /
 * detector output. **Not** a Python V3.1 dump.
 *
 * Mirrors `test/resources/real_frames/human_ground_truth.json` (docs snapshot).
 */
object RealFrameHumanGroundTruth {

    const val PROVENANCE =
        "HUMAN_VISUAL — authored from pvp_board.jpg inspection; NOT from VisionPipeline; NOT Python V3.1"

    enum class CellGtStatus {
        VERIFIED,
        UNVERIFIED,
        UNKNOWN,
    }

    data class CellGt(
        val row: Int,
        val col: Int,
        val color: TileColor,
        val shape: TileShape,
        val special: SpecialType,
        val colorStatus: CellGtStatus,
        val shapeStatus: CellGtStatus,
        val note: String = "",
    )

    /** 0-based row-major cells for rows 0..5 VERIFIED where clear; row 6 UNVERIFIED (toolbar crop). */
    val cells: List<CellGt> = buildList {
        fun add(
            r: Int,
            c: Int,
            color: TileColor,
            shape: TileShape,
            colorStatus: CellGtStatus = CellGtStatus.VERIFIED,
            shapeStatus: CellGtStatus = CellGtStatus.VERIFIED,
            special: SpecialType = SpecialType.NONE,
            note: String = "",
        ) {
            add(CellGt(r, c, color, shape, special, colorStatus, shapeStatus, note))
        }

        // Row 0
        add(0, 0, TileColor.B, TileShape.STAR)
        add(0, 1, TileColor.G, TileShape.DIAMOND)
        add(0, 2, TileColor.R, TileShape.CIRCLE)
        add(0, 3, TileColor.B, TileShape.STAR)
        add(0, 4, TileColor.B, TileShape.STAR)
        add(0, 5, TileColor.P, TileShape.SQUARE)
        add(0, 6, TileColor.B, TileShape.STAR)

        // Row 1
        add(1, 0, TileColor.R, TileShape.CIRCLE)
        add(1, 1, TileColor.Y, TileShape.TRIANGLE)
        add(1, 2, TileColor.R, TileShape.CIRCLE)
        add(1, 3, TileColor.O, TileShape.TRIANGLE, shapeStatus = CellGtStatus.UNVERIFIED,
            note = "inverted orange triangle; reconciler expects HEX")
        add(1, 4, TileColor.P, TileShape.SQUARE)
        add(1, 5, TileColor.R, TileShape.CIRCLE)
        add(1, 6, TileColor.B, TileShape.STAR)

        // Row 2
        add(2, 0, TileColor.G, TileShape.DIAMOND)
        add(2, 1, TileColor.P, TileShape.SQUARE)
        add(2, 2, TileColor.Y, TileShape.TRIANGLE)
        add(2, 3, TileColor.G, TileShape.DIAMOND)
        add(2, 4, TileColor.O, TileShape.TRIANGLE, shapeStatus = CellGtStatus.UNVERIFIED,
            note = "inverted orange triangle; reconciler expects HEX")
        add(2, 5, TileColor.O, TileShape.TRIANGLE, shapeStatus = CellGtStatus.UNVERIFIED,
            note = "inverted orange triangle; reconciler expects HEX")
        add(2, 6, TileColor.P, TileShape.SQUARE)

        // Row 3
        add(3, 0, TileColor.R, TileShape.CIRCLE)
        add(3, 1, TileColor.Y, TileShape.TRIANGLE)
        add(3, 2, TileColor.P, TileShape.SQUARE)
        add(3, 3, TileColor.R, TileShape.CIRCLE)
        add(3, 4, TileColor.Y, TileShape.TRIANGLE)
        add(3, 5, TileColor.P, TileShape.SQUARE)
        add(3, 6, TileColor.R, TileShape.CIRCLE)

        // Row 4 — mushroom at (4,1)
        add(4, 0, TileColor.G, TileShape.DIAMOND)
        add(
            4, 1, TileColor.UNKNOWN, TileShape.UNKNOWN,
            colorStatus = CellGtStatus.UNVERIFIED,
            shapeStatus = CellGtStatus.UNVERIFIED,
            note = "Purple Mushroom +3; SpecialType has no MUSHROOM",
        )
        add(4, 2, TileColor.O, TileShape.TRIANGLE, shapeStatus = CellGtStatus.UNVERIFIED,
            note = "inverted orange triangle; reconciler expects HEX")
        add(4, 3, TileColor.B, TileShape.STAR)
        add(4, 4, TileColor.Y, TileShape.TRIANGLE)
        add(4, 5, TileColor.G, TileShape.DIAMOND)
        add(4, 6, TileColor.O, TileShape.TRIANGLE, shapeStatus = CellGtStatus.UNVERIFIED,
            note = "inverted orange triangle; reconciler expects HEX")

        // Row 5
        add(5, 0, TileColor.Y, TileShape.TRIANGLE)
        add(5, 1, TileColor.O, TileShape.TRIANGLE, shapeStatus = CellGtStatus.UNVERIFIED,
            note = "inverted orange triangle; reconciler expects HEX")
        add(5, 2, TileColor.G, TileShape.DIAMOND)
        add(5, 3, TileColor.O, TileShape.TRIANGLE, shapeStatus = CellGtStatus.UNVERIFIED,
            note = "inverted orange triangle; reconciler expects HEX")
        add(5, 4, TileColor.B, TileShape.STAR)
        add(5, 5, TileColor.B, TileShape.STAR)
        add(5, 6, TileColor.P, TileShape.SQUARE)

        // Row 6 — UNVERIFIED (Android screenshot toolbar crop on primary)
        for (c in 0 until 7) {
            add(
                6, c, TileColor.UNKNOWN, TileShape.UNKNOWN,
                colorStatus = CellGtStatus.UNVERIFIED,
                shapeStatus = CellGtStatus.UNVERIFIED,
                note = "toolbar crop on primary — UNVERIFIED",
            )
        }
    }

    init {
        require(cells.size == 49) { "expected 49 cells, got ${cells.size}" }
    }

    fun cell(row: Int, col: Int): CellGt =
        cells.first { it.row == row && it.col == col }

    /**
     * Soft color agreement: among GT colorStatus=VERIFIED cells that the pipeline
     * did not mark unknown/occluded, count color matches. Never fails CI — returns rates.
     */
    fun softColorCompare(board: VisionBoard): SoftCompare {
        var checked = 0
        var compared = 0
        var matches = 0
        for (gt in cells) {
            if (gt.colorStatus != CellGtStatus.VERIFIED) continue
            checked++
            val got = board.get(gt.row, gt.col)
            if (got.isUnknown || got.occluded) continue
            compared++
            if (got.color == gt.color) matches++
        }
        return SoftCompare(checked, compared, matches)
    }

    data class SoftCompare(
        val verifiableGt: Int,
        val compared: Int,
        val matches: Int,
    ) {
        val rate: Float get() = if (compared == 0) 0f else matches.toFloat() / compared
    }
}
