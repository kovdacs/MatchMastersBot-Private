package com.match3vision.analyzer.vision

/**
 * Authored ground truth for [RealisticSyntheticFixture] — **REALISTIC_SYNTHETIC** only.
 *
 * **CRITICAL provenance:** every field is derived from fixture *construction parameters*
 * (what was drawn: palette index, occlusion set, authored ROI/boundaries). Values are
 * **never** copied from VisionPipeline / ColorDetector / ShapeDetector / etc. output.
 *
 * Where silhouette shape was not carved (solid shaded fills), [CellGtStatus.UNVERIFIED]
 * is used for shape. Occluded construction cells are [TileColor.UNKNOWN] + occluded=true.
 */
object RealisticSyntheticGroundTruth {

    enum class CellGtStatus {
        /** Drawn intentionally and expected to be recoverable in principle. */
        VERIFIED,
        /** Construction intent exists but detector-visible evidence is weak/absent. */
        UNVERIFIED,
        /** Intentionally unknown / occluded / uncertain by construction. */
        UNKNOWN,
    }

    data class CellGt(
        val row: Int,
        val col: Int,
        val color: TileColor,
        val shape: TileShape,
        val special: SpecialType,
        val occluded: Boolean,
        val colorStatus: CellGtStatus,
        val shapeStatus: CellGtStatus,
        val specialStatus: CellGtStatus,
        val note: String = "",
    )

    data class BoardGt(
        val label: String,
        val provenance: String,
        val boardRoiLeft: Int,
        val boardRoiTop: Int,
        val boardRoiRight: Int,
        val boardRoiBottom: Int,
        val xBoundaries: FloatArray,
        val yBoundaries: FloatArray,
        val cells: List<CellGt>,
        val expectedOccludedCount: Int,
        val expectedUnknownAtLeast: Int,
    ) {
        fun cell(row: Int, col: Int): CellGt =
            cells.first { it.row == row && it.col == col }
    }

    private val COLOR_TO_SHAPE: Map<TileColor, TileShape> = mapOf(
        TileColor.B to TileShape.STAR,
        TileColor.R to TileShape.CIRCLE,
        TileColor.Y to TileShape.TRIANGLE,
        TileColor.G to TileShape.DIAMOND,
        TileColor.P to TileShape.SQUARE,
        TileColor.O to TileShape.HEX,
    )

    /**
     * Build GT exclusively from [RealisticSyntheticFixture.Built] construction fields.
     */
    fun fromParams(built: RealisticSyntheticFixture.Built): BoardGt {
        val p = built.params
        val cells = ArrayList<CellGt>(49)
        for (r in 0 until 7) {
            for (c in 0 until 7) {
                val occluded = p.occludedCells.contains(r to c)
                if (occluded) {
                    cells += CellGt(
                        row = r,
                        col = c,
                        color = TileColor.UNKNOWN,
                        shape = TileShape.UNKNOWN,
                        special = SpecialType.NONE,
                        occluded = true,
                        colorStatus = CellGtStatus.UNKNOWN,
                        shapeStatus = CellGtStatus.UNKNOWN,
                        specialStatus = CellGtStatus.VERIFIED,
                        note = "Occluded by construction (${p.occlusionKind})",
                    )
                } else {
                    val color = indexToColor(p.colorIndices[r * 7 + c])
                    val expectedShape = COLOR_TO_SHAPE[color] ?: TileShape.UNKNOWN
                    cells += CellGt(
                        row = r,
                        col = c,
                        color = color,
                        shape = expectedShape,
                        special = SpecialType.NONE,
                        occluded = false,
                        // Color was painted from palette → VERIFIED from construction
                        colorStatus = CellGtStatus.VERIFIED,
                        // Solid shaded fill (no carved silhouette) → shape UNVERIFIED
                        // for silhouette detectors; reconciler may still fill expected pair.
                        shapeStatus = CellGtStatus.UNVERIFIED,
                        specialStatus = CellGtStatus.VERIFIED,
                        note = "Solid shaded fill; shape silhouette UNVERIFIED",
                    )
                }
            }
        }
        return BoardGt(
            label = RealisticSyntheticFixture.LABEL,
            provenance = RealisticSyntheticFixture.PROVENANCE,
            boardRoiLeft = built.boardRoi.left,
            boardRoiTop = built.boardRoi.top,
            boardRoiRight = built.boardRoi.right,
            boardRoiBottom = built.boardRoi.bottom,
            xBoundaries = built.authoredXBoundaries.copyOf(),
            yBoundaries = built.authoredYBoundaries.copyOf(),
            cells = cells,
            expectedOccludedCount = p.occludedCells.size,
            expectedUnknownAtLeast = p.occludedCells.size,
        )
    }

    private fun indexToColor(index: Int): TileColor = when (
        SyntheticFrames.PALETTE[index % SyntheticFrames.PALETTE.size]
    ) {
        SyntheticFrames.COLOR_B -> TileColor.B
        SyntheticFrames.COLOR_R -> TileColor.R
        SyntheticFrames.COLOR_Y -> TileColor.Y
        SyntheticFrames.COLOR_G -> TileColor.G
        SyntheticFrames.COLOR_P -> TileColor.P
        SyntheticFrames.COLOR_O -> TileColor.O
        else -> TileColor.UNKNOWN
    }

    /** Compact JSON-ish dump for CI logs / docs (not loaded as READY parity). */
    fun toDiagnosticJson(gt: BoardGt): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"label\": \"").append(gt.label).append("\",\n")
        sb.append("  \"provenance\": \"").append(gt.provenance).append("\",\n")
        sb.append("  \"boardRoi\": [")
            .append(gt.boardRoiLeft).append(',')
            .append(gt.boardRoiTop).append(',')
            .append(gt.boardRoiRight).append(',')
            .append(gt.boardRoiBottom).append("],\n")
        sb.append("  \"expectedOccludedCount\": ").append(gt.expectedOccludedCount).append(",\n")
        sb.append("  \"cells\": [\n")
        gt.cells.forEachIndexed { i, cell ->
            sb.append("    {\"r\":").append(cell.row)
                .append(",\"c\":").append(cell.col)
                .append(",\"color\":\"").append(cell.color).append('"')
                .append(",\"shape\":\"").append(cell.shape).append('"')
                .append(",\"special\":\"").append(cell.special).append('"')
                .append(",\"occ\":").append(cell.occluded)
                .append(",\"colorStatus\":\"").append(cell.colorStatus).append('"')
                .append(",\"shapeStatus\":\"").append(cell.shapeStatus).append('"')
                .append("}")
            if (i < gt.cells.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append("  ]\n}")
        return sb.toString()
    }
}
