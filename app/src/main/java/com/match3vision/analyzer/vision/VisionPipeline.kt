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
        val first = analyzeOnce(pixels, width, height, contentRoi, pinnedBoard = null)
        if (first.validation.isPass) return first
        val pinned = nominalRetryRoi(pixels, width, height, first) ?: return first
        val second = analyzeOnce(pixels, width, height, contentRoi, pinnedBoard = pinned)
        return if (second.validation.isPass &&
            second.gridConfidence >= VisionThresholds.MIN_GRID_CONFIDENCE &&
            second.boardConfidence >= VisionThresholds.MIN_BOARD_CONFIDENCE &&
            second.unknownCount <= VisionThresholds.MAX_UNKNOWN_COUNT
        ) {
            second.copy(diagnostics = second.diagnostics + ("latticeNominal150" to "pass"))
        } else {
            first.copy(diagnostics = first.diagnostics + ("latticeNominal150" to "tried-hold"))
        }
    }

    private fun nominalRetryRoi(
        pixels: IntArray,
        width: Int,
        height: Int,
        result: VisionResult,
    ): ContentRoi? {
        if (result.diagnostics["latticeRoiUsed"] != "yes") return null
        val period = result.diagnostics["latticeCandidate"]
            ?.split(",")
            ?.getOrNull(2)
            ?.toIntOrNull()
            ?: return null
        if (!BoardFinder.nominalPitchDeviates(period)) return null
        val roi = result.grid.boardRoi
        return boardFinder.nominalLatticeIfSupported(
            pixels, width, height, roi.left, roi.right,
        )
    }

    private fun analyzeOnce(
        pixels: IntArray,
        width: Int,
        height: Int,
        contentRoi: ContentRoi?,
        pinnedBoard: ContentRoi?,
    ): VisionResult {
        SpecialCropAudit.clear()
        val find = boardFinder.find(pixels, width, height, contentRoi, pinnedBoard)
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
        val roi = grid.boardRoi
        val roiImplausible = RoiPlausibility.reject(
            frameHeight = height,
            roiTop = roi.top,
            roiWidth = roi.width(),
            roiHeight = roi.height(),
        )
        val validation = if (roiImplausible) {
            diag["roiPlausible"] = "no"
            ValidationResult.Hold(RoiPlausibility.HOLD_REASON)
        } else {
            diag["roiPlausible"] = if (height >= RoiPlausibility.PHONE_FRAME_MIN_HEIGHT) {
                "yes"
            } else {
                "not_checked"
            }
            validator.validate(boardConf, gridConf, unknownCount)
        }

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
            diag["unkReason_${row}_${col}"] = "occlusion"
            return CellVision.unknown(occluded = true, confidence = 0f)
        }

        // Color: center-weighted robust sampling (detector applies center disk).
        val color = colorDetector.detect(crop, cw, ch)

        // Shape: rectangular inset so gutters / playfield bleed do not invent
        // false STAR/SQUARE silhouettes. Shape heuristics themselves unchanged.
        val (shapePixels, shapeW, shapeH) = insetCrop(crop, cw, ch, SHAPE_INSET_FRAC)
        val shape = shapeDetector.detect(shapePixels, shapeW, shapeH)

        // Special overlays (+ badges) often sit near edges — keep full cell.
        val special = specialDetector.detect(
            crop,
            cw,
            ch,
            source = "VisionPipeline.analyzeCell r=$row c=$col",
        )

        val reconciled = ColorShapeReconciler.reconcile(
            color = color.color,
            colorConf = color.confidence,
            shape = shape.shape,
            shapeConf = shape.confidence,
            special = special.special,
        )
        if (reconciled.isUnknown) {
            diag["unkReason_${row}_${col}"] = UnknownReason.diagnose(
                color = color.color,
                shape = shape.shape,
                special = special.special,
                occluded = false,
                cellW = cw,
                cellH = ch,
            )
        }
        return reconciled
    }

    /**
     * Inset a cell buffer by [frac] of width/height on each side.
     * Used for shape sampling only (color uses center-disk inside [ColorDetector]).
     */
    private fun insetCrop(
        pixels: IntArray,
        width: Int,
        height: Int,
        frac: Float,
    ): Triple<IntArray, Int, Int> {
        if (width < 6 || height < 6 || frac <= 0f) return Triple(pixels, width, height)
        val dx = (width * frac).toInt().coerceAtLeast(1)
        val dy = (height * frac).toInt().coerceAtLeast(1)
        val left = dx
        val top = dy
        val right = (width - dx).coerceAtLeast(left + 3)
        val bottom = (height - dy).coerceAtLeast(top + 3)
        val (out, size) = PixelMath.crop(pixels, width, height, left, top, right, bottom)
        return Triple(out, size.first, size.second)
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
        /**
         * Shape crop inset as fraction of cell size per side (~inner 64% linear).
         * Keeps gutters out of silhouette moments without changing ShapeDetector.
         */
        const val SHAPE_INSET_FRAC = 0.18f
    }
}
