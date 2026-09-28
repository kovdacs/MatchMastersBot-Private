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
        val boardConf = boardConfidence(board, grid)
        val gridConf = grid.confidence
        // Combined confidence: blend board + grid (board-weighted)
        val combined = (boardConf * 0.6f + gridConf * 0.4f).coerceIn(0f, 1f)
        val validation = validator.validate(boardConf, gridConf, unknownCount)

        diag["unknownCount"] = unknownCount.toString()
        diag["boardConfidence"] = "%.4f".format(boardConf)
        diag["gridConfidence"] = "%.4f".format(gridConf)
        diag["validation"] = if (validation.isPass) "PASS" else "HOLD"

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
        var left = cell.left.toInt().coerceIn(0, width)
        var top = cell.top.toInt().coerceIn(0, height)
        var right = cell.right.toInt().coerceIn(left, width)
        var bottom = cell.bottom.toInt().coerceIn(top, height)
        // Inset crop toward cell center to exclude dark gutters / board chrome that
        // otherwise trip OcclusionDetector partial_dark on valid gems (purple bg).
        val bw = (right - left).coerceAtLeast(1)
        val bh = (bottom - top).coerceAtLeast(1)
        val insetX = (bw * CELL_CROP_INSET_FRAC).toInt().coerceAtLeast(0)
        val insetY = (bh * CELL_CROP_INSET_FRAC).toInt().coerceAtLeast(0)
        if (bw > insetX * 2 + 4 && bh > insetY * 2 + 4) {
            left += insetX
            right -= insetX
            top += insetY
            bottom -= insetY
        }
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
    private fun boardConfidence(board: VisionBoard, grid: GridGeometry): Float {
        val mean = board.meanConfidence()
        val unknowns = board.unknownCount()
        val unknownPenalty = unknowns * 0.08f
        val methodBonus = when (grid.method) {
            GridMethod.PROJECTION -> 0.05f
            GridMethod.EVEN_SPLIT -> 0f
        }
        // If nearly all cells known with good mean, saturate near 1 for gate
        val base = if (unknowns <= VisionThresholds.MAX_UNKNOWN_COUNT && mean >= 0.75f) {
            (0.90f + mean * 0.10f + methodBonus - unknownPenalty)
        } else {
            (mean + methodBonus - unknownPenalty)
        }
        return base.coerceIn(0f, 1f)
    }

    companion object {
        /**
         * Fraction of cell width/height trimmed from each side before occlusion/color/shape.
         * Keeps detectors on gem body instead of purple gutters (partial_dark false positives).
         */
        const val CELL_CROP_INSET_FRAC = 0.10f
    }
}
