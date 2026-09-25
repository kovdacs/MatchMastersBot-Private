package com.match3vision.analyzer.vision.parity

import com.match3vision.analyzer.capture.ContentRoi
import com.match3vision.analyzer.vision.CellGeometry
import com.match3vision.analyzer.vision.CellVision
import com.match3vision.analyzer.vision.GridGeometry
import com.match3vision.analyzer.vision.GridMethod
import com.match3vision.analyzer.vision.SpecialType
import com.match3vision.analyzer.vision.TileColor
import com.match3vision.analyzer.vision.TileShape
import com.match3vision.analyzer.vision.ValidationResult
import com.match3vision.analyzer.vision.VisionBoard
import com.match3vision.analyzer.vision.VisionResult
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Compares Android [VisionResult] against a reference snapshot.
 * Metrics are reported **separately** — never blended into one score.
 *
 * Synthetic in-test references are OK. Real Python V3.1 dumps are
 * [REFERENCE_PENDING] until provided.
 */
object VisionParityComparator {

    const val REFERENCE_PENDING = "REFERENCE_PENDING"

    data class RoiMetric(val match: Boolean, val maxAbsDelta: Float)
    data class BoundariesMetric(
        val xMaxAbsDelta: Float,
        val yMaxAbsDelta: Float,
        val xMeanAbsDelta: Float,
        val yMeanAbsDelta: Float,
    )
    data class CellBoxesMetric(val meanAbsEdgeDelta: Float, val meanIoU: Float)
    data class LabelMetric(val matchRate: Float, val matched: Int, val total: Int)
    data class UnknownMetric(val androidCount: Int, val referenceCount: Int, val absDelta: Int)
    data class GateMetric(val androidGate: String, val referenceGate: String, val match: Boolean)

    data class ParityReport(
        val status: String,
        val roi: RoiMetric?,
        val boundaries: BoundariesMetric?,
        val cellBoxes: CellBoxesMetric?,
        val color: LabelMetric?,
        val shape: LabelMetric?,
        val special: LabelMetric?,
        val occlusion: LabelMetric?,
        val unknownCount: UnknownMetric?,
        val gate: GateMetric?,
    )

    /** Reference object constructed in tests or loaded from JSON (when READY). */
    data class ReferenceSnapshot(
        val status: String = REFERENCE_PENDING,
        val imageWidth: Int? = null,
        val imageHeight: Int? = null,
        val letterboxRoi: ContentRoi? = null,
        val boardRoi: ContentRoi? = null,
        val gridMethod: GridMethod? = null,
        val gridConfidence: Float? = null,
        val xBoundaries: FloatArray? = null,
        val yBoundaries: FloatArray? = null,
        val cellBoxes: List<CellGeometry>? = null,
        val cells: List<CellVision>? = null,
        val unknownCount: Int? = null,
        val gate: String? = null,
    )

    fun compare(
        android: VisionResult,
        reference: ReferenceSnapshot,
        letterboxRoi: ContentRoi? = null,
    ): ParityReport {
        if (reference.status == REFERENCE_PENDING ||
            (reference.xBoundaries == null && reference.cells == null && reference.gate == null)
        ) {
            // Still allow metric computation when synthetic fields are filled in-test
            // with status READY; pending with empty fields short-circuits.
            if (reference.status == REFERENCE_PENDING &&
                reference.xBoundaries == null &&
                reference.cells == null &&
                reference.gate == null
            ) {
                return ParityReport(
                    status = REFERENCE_PENDING,
                    roi = null,
                    boundaries = null,
                    cellBoxes = null,
                    color = null,
                    shape = null,
                    special = null,
                    occlusion = null,
                    unknownCount = null,
                    gate = null,
                )
            }
        }

        val roiMetric = if (reference.boardRoi != null) {
            val a = android.grid.boardRoi
            val b = reference.boardRoi
            val d = maxOf(
                abs(a.left - b.left),
                abs(a.top - b.top),
                abs(a.right - b.right),
                abs(a.bottom - b.bottom),
            ).toFloat()
            RoiMetric(match = d == 0f, maxAbsDelta = d)
        } else if (letterboxRoi != null && reference.letterboxRoi != null) {
            val a = letterboxRoi
            val b = reference.letterboxRoi
            val d = maxOf(
                abs(a.left - b.left),
                abs(a.top - b.top),
                abs(a.right - b.right),
                abs(a.bottom - b.bottom),
            ).toFloat()
            RoiMetric(match = d == 0f, maxAbsDelta = d)
        } else null

        val boundaries = if (reference.xBoundaries != null && reference.yBoundaries != null) {
            BoundariesMetric(
                xMaxAbsDelta = maxAbsDelta(android.grid.xBoundaries, reference.xBoundaries),
                yMaxAbsDelta = maxAbsDelta(android.grid.yBoundaries, reference.yBoundaries),
                xMeanAbsDelta = meanAbsDelta(android.grid.xBoundaries, reference.xBoundaries),
                yMeanAbsDelta = meanAbsDelta(android.grid.yBoundaries, reference.yBoundaries),
            )
        } else null

        val cellBoxes = if (reference.cellBoxes != null && reference.cellBoxes.size == 49) {
            val androidBoxes = android.grid.cells()
            var edgeSum = 0f
            var iouSum = 0f
            for (i in 0 until 49) {
                val a = androidBoxes[i]
                val b = reference.cellBoxes[i]
                edgeSum += (abs(a.left - b.left) + abs(a.top - b.top) +
                    abs(a.right - b.right) + abs(a.bottom - b.bottom)) / 4f
                iouSum += iou(a, b)
            }
            CellBoxesMetric(meanAbsEdgeDelta = edgeSum / 49f, meanIoU = iouSum / 49f)
        } else null

        val refCells = reference.cells
        val color = if (refCells != null && refCells.size == 49) {
            labelRate(49) { i ->
                val (r, c) = i / 7 to i % 7
                android.board.get(r, c).color.name == refCells[i].color.name
            }
        } else null
        val shape = if (refCells != null && refCells.size == 49) {
            labelRate(49) { i ->
                val (r, c) = i / 7 to i % 7
                android.board.get(r, c).shape.name == refCells[i].shape.name
            }
        } else null
        val special = if (refCells != null && refCells.size == 49) {
            labelRate(49) { i ->
                val (r, c) = i / 7 to i % 7
                android.board.get(r, c).special.name == refCells[i].special.name
            }
        } else null
        val occlusion = if (refCells != null && refCells.size == 49) {
            labelRate(49) { i ->
                val (r, c) = i / 7 to i % 7
                android.board.get(r, c).occluded == refCells[i].occluded
            }
        } else null

        val unknown = if (reference.unknownCount != null) {
            UnknownMetric(
                androidCount = android.unknownCount,
                referenceCount = reference.unknownCount,
                absDelta = abs(android.unknownCount - reference.unknownCount),
            )
        } else null

        val androidGate = when (android.validation) {
            is ValidationResult.Pass -> "PASS"
            is ValidationResult.Hold -> "HOLD"
        }
        val gate = if (reference.gate != null) {
            GateMetric(androidGate, reference.gate, androidGate == reference.gate)
        } else null

        return ParityReport(
            status = if (reference.status == REFERENCE_PENDING) REFERENCE_PENDING else "COMPARED",
            roi = roiMetric,
            boundaries = boundaries,
            cellBoxes = cellBoxes,
            color = color,
            shape = shape,
            special = special,
            occlusion = occlusion,
            unknownCount = unknown,
            gate = gate,
        )
    }

    private fun labelRate(n: Int, pred: (Int) -> Boolean): LabelMetric {
        var m = 0
        for (i in 0 until n) if (pred(i)) m++
        return LabelMetric(matchRate = m.toFloat() / n, matched = m, total = n)
    }

    private fun maxAbsDelta(a: FloatArray, b: FloatArray): Float {
        val n = min(a.size, b.size)
        var m = 0f
        for (i in 0 until n) m = max(m, abs(a[i] - b[i]))
        return m
    }

    private fun meanAbsDelta(a: FloatArray, b: FloatArray): Float {
        val n = min(a.size, b.size)
        if (n == 0) return 0f
        var s = 0f
        for (i in 0 until n) s += abs(a[i] - b[i])
        return s / n
    }

    private fun iou(a: CellGeometry, b: CellGeometry): Float {
        val ix1 = max(a.left, b.left)
        val iy1 = max(a.top, b.top)
        val ix2 = min(a.right, b.right)
        val iy2 = min(a.bottom, b.bottom)
        val iw = max(0f, ix2 - ix1)
        val ih = max(0f, iy2 - iy1)
        val inter = iw * ih
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }
}
