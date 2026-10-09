package com.match3vision.analyzer.vision

import com.match3vision.analyzer.capture.ContentRoi

/**
 * How the 7×7 grid boundaries were obtained.
 *
 * - [PROJECTION]: primary — row/column intensity or edge projection → 8 lines
 * - [EVEN_SPLIT]: official fallback — equal division of [GridGeometry.boardRoi]
 *   (cells need NOT be square). Square-snap, if ever used, is folded into EVEN_SPLIT
 *   and noted in diagnostics only.
 */
enum class GridMethod {
    PROJECTION,
    EVEN_SPLIT,
}

/**
 * One cell rectangle in **frame pixel coordinates** (same space as [ContentRoi]
 * and [CaptureFrame](com.match3vision.analyzer.capture.CaptureFrame)).
 */
data class CellGeometry(
    val row: Int,
    val col: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(row in 0 until GridGeometry.GRID_SIZE) { "row out of range: $row" }
        require(col in 0 until GridGeometry.GRID_SIZE) { "col out of range: $col" }
        require(right >= left) { "right < left" }
        require(bottom >= top) { "bottom < top" }
    }

    fun width(): Float = right - left
    fun height(): Float = bottom - top
    fun centerX(): Float = (left + right) * 0.5f
    fun centerY(): Float = (top + bottom) * 0.5f
}

/**
 * 7×7 board grid geometry in **frame pixel coordinates**.
 *
 * [xBoundaries] / [yBoundaries] each have length 8 (outer edges + 6 internal gutters).
 * Cells are derived via [cellBox]; they are not forced to be square.
 */
data class GridGeometry(
    val xBoundaries: FloatArray,
    val yBoundaries: FloatArray,
    val method: GridMethod,
    val confidence: Float,
    val boardRoi: ContentRoi,
) {
    init {
        require(xBoundaries.size == BOUNDARY_COUNT) {
            "xBoundaries must have $BOUNDARY_COUNT values, got ${xBoundaries.size}"
        }
        require(yBoundaries.size == BOUNDARY_COUNT) {
            "yBoundaries must have $BOUNDARY_COUNT values, got ${yBoundaries.size}"
        }
        require(confidence in 0f..1f) { "confidence out of range" }
    }

    fun cellBox(row: Int, col: Int): CellGeometry {
        require(row in 0 until GRID_SIZE && col in 0 until GRID_SIZE) {
            "cell ($row,$col) out of 0..${GRID_SIZE - 1}"
        }
        return CellGeometry(
            row = row,
            col = col,
            left = xBoundaries[col],
            top = yBoundaries[row],
            right = xBoundaries[col + 1],
            bottom = yBoundaries[row + 1],
        )
    }

    /** All 49 cell boxes in row-major order. */
    fun cells(): List<CellGeometry> = buildList {
        for (r in 0 until GRID_SIZE) {
            for (c in 0 until GRID_SIZE) add(cellBox(r, c))
        }
    }

    fun isMonotonic(): Boolean =
        isStrictlyIncreasing(xBoundaries) && isStrictlyIncreasing(yBoundaries)

    /**
     * Relative spacing variance for cell widths and heights.
     * Returns true when both axes have acceptable uniformity.
     */
    fun spacingOk(maxRelVariance: Float = DEFAULT_MAX_REL_VARIANCE): Boolean {
        val xOk = relativeSpacingVariance(xBoundaries) <= maxRelVariance
        val yOk = relativeSpacingVariance(yBoundaries) <= maxRelVariance
        return xOk && yOk
    }

    fun validate(maxRelVariance: Float = DEFAULT_MAX_REL_VARIANCE): Boolean =
        isMonotonic() && spacingOk(maxRelVariance)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GridGeometry) return false
        return method == other.method &&
            confidence == other.confidence &&
            boardRoi == other.boardRoi &&
            xBoundaries.contentEquals(other.xBoundaries) &&
            yBoundaries.contentEquals(other.yBoundaries)
    }

    override fun hashCode(): Int {
        var result = xBoundaries.contentHashCode()
        result = 31 * result + yBoundaries.contentHashCode()
        result = 31 * result + method.hashCode()
        result = 31 * result + confidence.hashCode()
        result = 31 * result + boardRoi.hashCode()
        return result
    }

    companion object {
        const val GRID_SIZE = 7
        const val BOUNDARY_COUNT = GRID_SIZE + 1 // 8
        const val DEFAULT_MAX_REL_VARIANCE = 0.12f

        fun isStrictlyIncreasing(values: FloatArray): Boolean {
            for (i in 1 until values.size) {
                if (values[i] <= values[i - 1]) return false
            }
            return true
        }

        /**
         * Variance of consecutive spacings divided by mean² (relative).
         * 0 = perfectly uniform.
         */
        fun relativeSpacingVariance(boundaries: FloatArray): Float {
            if (boundaries.size < 2) return Float.MAX_VALUE
            val n = boundaries.size - 1
            val spacings = FloatArray(n) { i -> boundaries[i + 1] - boundaries[i] }
            val mean = spacings.average().toFloat()
            if (mean <= 1e-6f) return Float.MAX_VALUE
            var sumSq = 0.0
            for (s in spacings) {
                val d = (s - mean).toDouble()
                sumSq += d * d
            }
            val variance = (sumSq / n).toFloat()
            return variance / (mean * mean)
        }

        /**
         * Build EVEN_SPLIT geometry over [boardRoi] in full-frame pixels.
         * [boardRoi.left] and [boardRoi.top] are added here. Centres are not ROI-relative.
         */
        fun evenSplit(boardRoi: ContentRoi, confidence: Float = 0.70f): GridGeometry {
            val x = FloatArray(BOUNDARY_COUNT) { i ->
                boardRoi.left + i * boardRoi.width().toFloat() / GRID_SIZE
            }
            val y = FloatArray(BOUNDARY_COUNT) { i ->
                boardRoi.top + i * boardRoi.height().toFloat() / GRID_SIZE
            }
            return GridGeometry(x, y, GridMethod.EVEN_SPLIT, confidence.coerceIn(0f, 1f), boardRoi)
        }
    }
}

/**
 * Tile base color labels for this research prototype's target game palette.
 *
 * Mapping is **game-specific** (blue/red markers etc.) — do not generalize to
 * arbitrary match-3 titles without recalibration.
 */
enum class TileColor {
    B, // blue
    R, // red
    Y, // yellow
    G, // green
    P, // purple
    O, // orange
    UNKNOWN,
}

/**
 * Tile shape labels paired with [TileColor] via [ColorShapeReconciler].
 */
enum class TileShape {
    STAR,
    CIRCLE,
    TRIANGLE,
    DIAMOND,
    SQUARE,
    HEX,
    UNKNOWN,
}

/**
 * Special overlay on a cell. Prefer [NONE] over false positives
 * (see [SpecialDetector] confidence floor 0.55).
 */
enum class SpecialType {
    NONE,
    TWO_WAY_ARROW,
    LIGHTNING,
    BOMB,
}

/**
 * Per-cell vision output after occlusion → color → shape → special → reconcile.
 */
data class CellVision(
    val color: TileColor,
    val shape: TileShape,
    val special: SpecialType,
    val occluded: Boolean,
    val confidence: Float,
    val isUnknown: Boolean,
) {
    companion object {
        fun unknown(occluded: Boolean = false, confidence: Float = 0f) = CellVision(
            color = TileColor.UNKNOWN,
            shape = TileShape.UNKNOWN,
            special = SpecialType.NONE,
            occluded = occluded,
            confidence = confidence.coerceIn(0f, 1f),
            isUnknown = true,
        )
    }
}

/** 7×7 board of [CellVision]. */
data class VisionBoard(
    val cells: Array<Array<CellVision>>,
) {
    init {
        require(cells.size == GridGeometry.GRID_SIZE) { "rows must be 7" }
        require(cells.all { it.size == GridGeometry.GRID_SIZE }) { "each row must be 7" }
    }

    fun get(row: Int, col: Int): CellVision = cells[row][col]

    fun unknownCount(): Int {
        var n = 0
        for (r in 0 until GridGeometry.GRID_SIZE) {
            for (c in 0 until GridGeometry.GRID_SIZE) {
                if (cells[r][c].isUnknown) n++
            }
        }
        return n
    }

    fun meanConfidence(): Float {
        var sum = 0f
        var n = 0
        for (r in 0 until GridGeometry.GRID_SIZE) {
            for (c in 0 until GridGeometry.GRID_SIZE) {
                sum += cells[r][c].confidence
                n++
            }
        }
        return if (n == 0) 0f else sum / n
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VisionBoard) return false
        return cells.contentDeepEquals(other.cells)
    }

    override fun hashCode(): Int = cells.contentDeepHashCode()

    companion object {
        fun filled(cell: CellVision): VisionBoard {
            val rows = Array(GridGeometry.GRID_SIZE) {
                Array(GridGeometry.GRID_SIZE) { cell }
            }
            return VisionBoard(rows)
        }
    }
}

/**
 * Validation gate outcome. HOLD blocks Decision AI (message only in Phase 2 —
 * no Decision AI implementation here).
 */
sealed class ValidationResult {
    data object Pass : ValidationResult()
    data class Hold(val reason: String) : ValidationResult()

    val isPass: Boolean get() = this is Pass
}

/**
 * Full vision pipeline result for one frame.
 *
 * @property confidence Combined board recognition confidence (mean cell + grid blend).
 * @property diagnostics Free-form key→value notes (method details, fallbacks, etc.).
 */
data class VisionResult(
    val board: VisionBoard,
    val grid: GridGeometry,
    val unknownCount: Int,
    val confidence: Float,
    val boardConfidence: Float,
    val gridConfidence: Float,
    val validation: ValidationResult,
    val method: GridMethod,
    val diagnostics: Map<String, String> = emptyMap(),
)

/**
 * Configurable confidence gates (ARCHITECTURE.md defaults).
 */
object VisionThresholds {
    const val MIN_BOARD_CONFIDENCE = 0.95f
    const val MIN_GRID_CONFIDENCE = 0.98f
    const val MAX_UNKNOWN_COUNT = 1
    const val SPECIAL_MIN_CONFIDENCE = 0.62f
    /** High-confidence color↔shape contradiction → force UNKNOWN. */
    const val RECONCILE_HIGH_CONFIDENCE = 0.70f
}
