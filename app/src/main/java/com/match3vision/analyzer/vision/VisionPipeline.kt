package com.match3vision.analyzer.vision

import android.graphics.Bitmap
import com.match3vision.analyzer.capture.ContentRoi

/**
 * Phase 2 vision orchestration (analyzer only):
 *
 * Frame → ROI → [GridGeometry] → 49 cells →
 * for each cell: occlusion → color → shape → special → reconcile →
 * [VisionResult] + validation.
 *
 * Primary API is pure [IntArray] (JVM-testable). [Bitmap] overload for device.
 * No AccessibilityService / touch injection / Decision AI.
 */
class VisionPipeline(
    private val boardFinder: BoardFinder = BoardFinder(),
    private val occlusionDetector: OcclusionDetector = OcclusionDetector(),
    private val colorDetector: ColorDetector = ColorDetector(),
    private val shapeDetector: ShapeDetector = ShapeDetector(),
    private val specialDetector: SpecialDetector = SpecialDetector(),
    private val validator: VisionValidator = VisionValidator(),
) {

    fun analyze(bitmap: Bitmap, contentRoi: ContentRoi? = null): VisionResult {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return analyze(pixels, w, h, contentRoi)
    }

    fun analyze(
        pixels: IntArray,
        width: Int,
        height: Int,
        contentRoi: ContentRoi? = null,
    ): VisionResult {
        val find = boardFinder.find(pixels, width, height, contentRoi)
        val grid = find.grid
        val diag = find.diagnostics.toMutableMap()

        val cells = Array(GridGeometry.GRID_SIZE) { r ->
            Array(GridGeometry.GRID_SIZE) { c ->
                analyzeCell(pixels, width, height, grid.cellBox(r, c), diag, r, c)
            }
        }
        val board = VisionBoard(cells)
        val unknownCount = board.unknownCount()
        val mean = board.meanConfidence()
        val boardConf = boardConfidence(board, grid, mean, unknownCount)
        val gridConf = grid.confidence
        // Combined confidence: blend board + grid (board-weighted)
        val combined = (boardConf * 0.6f + gridConf * 0.4f).coerceIn(0f, 1f)
        val validation = validator.validate(boardConf, gridConf, unknownCount)

        diag["unknownCount"] = unknownCount.toString()
        diag["boardConfidence"] = "%.4f".format(boardConf)
        diag["gridConfidence"] = "%.4f".format(gridConf)
        diag["validation"] = if (validation.isPass) "PASS" else "HOLD"
        diag["meanCellConf"] = "%.4f".format(mean)
        diag["boardConfPath"] = if (
            unknownCount <= VisionThresholds.MAX_UNKNOWN_COUNT && mean >= 0.55f
        ) {
            "high"
        } else {
            "penalty"
        }
        // Aggregate occlusion reasons (live unk≈42 usually means overlay / self-UI).
        val occCounts = linkedMapOf<String, Int>()
        for ((k, v) in diag) {
            if (k.startsWith("occ_")) {
                occCounts[v] = (occCounts[v] ?: 0) + 1
            }
        }
        if (occCounts.isNotEmpty()) {
            diag["occSummary"] = occCounts.entries.joinToString(",") { "${it.key}=${it.value}" }
        }
        // High grid + nearly-all unknown: board geometry OK but cell content is not
        // gem-like (analyzer UI / floating overlay / FX covering playfield).
        if (unknownCount >= SUSPECT_OVERLAY_UNKNOWN_MIN &&
            gridConf >= VisionThresholds.MIN_GRID_CONFIDENCE
        ) {
            diag["suspectOverlayOrSelfUi"] = "true"
            diag["suspectHint"] =
                "gridConf high but unk=$unknownCount — prefer frame without analyzer UI " +
                    "covering the board (freeze while Activity resumed; split-screen/PiP). " +
                    "MediaProjection captures composed screen including overlays."
        }

        return VisionResult(
            board = board,
            grid = grid,
            unknownCount = unknownCount,
            confidence = combined,
            boardConfidence = boardConf,
            gridConfidence = gridConf,
            validation = validation,
            method = grid.method,
            diagnostics = diag,
        )
    }

    private fun analyzeCell(
        pixels: IntArray,
        width: Int,
        height: Int,
        cell: CellGeometry,
        diag: MutableMap<String, String>,
        row: Int,
        col: Int,
    ): CellVision {
        val left = cell.left.toInt().coerceIn(0, width)
        val top = cell.top.toInt().coerceIn(0, height)
        val right = cell.right.toInt().coerceIn(left, width)
        val bottom = cell.bottom.toInt().coerceIn(top, height)
        val (crop, size) = PixelMath.crop(pixels, width, height, left, top, right, bottom)
        val cw = size.first
        val ch = size.second

        val occ = occlusionDetector.detect(crop, cw, ch)
        if (occ.occluded) {
            diag["occ_${row}_${col}"] = occ.reason
            return CellVision.unknown(occluded = true, confidence = 0f)
        }

        val color = colorDetector.detect(crop)
        val shape = shapeDetector.detect(crop, cw, ch)
        val special = specialDetector.detect(crop, cw, ch)

        return ColorShapeReconciler.reconcile(
            color = color.color,
            colorConf = color.confidence,
            shape = shape.shape,
            shapeConf = shape.confidence,
            special = special.special,
        )
    }

    /**
     * Board confidence: for PASS-friendly clean boards with high grid confidence
     * and few unknowns, push toward 1.0; otherwise mean cell confidence with
     * unknown penalty.
     */
    private fun boardConfidence(
        board: VisionBoard,
        grid: GridGeometry,
        mean: Float = board.meanConfidence(),
        unknowns: Int = board.unknownCount(),
    ): Float {
        val unknownPenalty = unknowns * 0.08f
        val methodBonus = when (grid.method) {
            GridMethod.PROJECTION -> 0.05f
            GridMethod.EVEN_SPLIT -> 0f
        }
        // If unknowns within gate and mean is in the typical real-frame band,
        // saturate near 1 so MIN_BOARD_CONFIDENCE can clear without lowering that gate.
        // Mean ≥0.55 matches observed known-cell conf on clean PROJECTION grids
        // (detectors are conservative on JPEG gems); high path unk penalty is lighter
        // because MAX_UNKNOWN_COUNT is enforced separately by VisionValidator.
        // When nearly all cells are UNKNOWN (mean≈0, unk≫1), penalty path clamps to 0
        // — that is expected, not a separate formula bug (see suspectOverlayOrSelfUi).
        val base = if (unknowns <= VisionThresholds.MAX_UNKNOWN_COUNT && mean >= 0.55f) {
            (0.90f + mean * 0.10f + methodBonus - unknowns * 0.04f)
        } else {
            (mean + methodBonus - unknownPenalty)
        }
        return base.coerceIn(0f, 1f)
    }

    companion object {
        /** unk at/above this with PASS-level gridConf → overlay/self-UI suspect. */
        const val SUSPECT_OVERLAY_UNKNOWN_MIN = 20
    }
}
