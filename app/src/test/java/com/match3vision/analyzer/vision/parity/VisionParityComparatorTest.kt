package com.match3vision.analyzer.vision.parity

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import com.match3vision.analyzer.vision.CellVision
import com.match3vision.analyzer.vision.GridGeometry
import com.match3vision.analyzer.vision.GridMethod
import com.match3vision.analyzer.vision.SpecialType
import com.match3vision.analyzer.vision.TileColor
import com.match3vision.analyzer.vision.TileShape
import com.match3vision.analyzer.vision.ValidationResult
import com.match3vision.analyzer.vision.VisionBoard
import com.match3vision.analyzer.vision.VisionResult
import org.junit.Test

/**
 * Uses synthetic Android-side results vs synthetic reference objects constructed
 * in-test. These are NOT real Python V3.1 dumps.
 */
class VisionParityComparatorTest {

    private fun syntheticResult(
        unknown: Int = 0,
        gatePass: Boolean = true,
    ): VisionResult {
        val roi = ContentRoi(10, 20, 710, 720)
        val grid = GridGeometry.evenSplit(roi, confidence = 0.99f).copy(
            // evenSplit returns EVEN_SPLIT; force high conf for gate tests
        )
        val known = CellVision(
            color = TileColor.B,
            shape = TileShape.STAR,
            special = SpecialType.NONE,
            occluded = false,
            confidence = 0.95f,
            isUnknown = false,
        )
        val cells = Array(7) { r ->
            Array(7) { c ->
                if (unknown > 0 && r == 0 && c < unknown) CellVision.unknown() else known
            }
        }
        val board = VisionBoard(cells)
        return VisionResult(
            board = board,
            grid = grid,
            unknownCount = board.unknownCount(),
            confidence = 0.97f,
            boardConfidence = 0.97f,
            gridConfidence = grid.confidence,
            validation = if (gatePass) ValidationResult.Pass else ValidationResult.Hold("test"),
            method = grid.method,
        )
    }

    @Test
    fun pendingReference_returnsReferencePending() {
        val android = syntheticResult()
        val ref = VisionParityComparator.ReferenceSnapshot(status = VisionParityComparator.REFERENCE_PENDING)
        val report = VisionParityComparator.compare(android, ref)
        assertThat(report.status).isEqualTo(VisionParityComparator.REFERENCE_PENDING)
        assertThat(report.color).isNull()
        assertThat(report.gate).isNull()
    }

    @Test
    fun perfectSyntheticMatch_separateMetricsAllAgree() {
        val android = syntheticResult(unknown = 0, gatePass = true)
        val cells = mutableListOf<CellVision>()
        for (r in 0 until 7) for (c in 0 until 7) cells.add(android.board.get(r, c))
        val ref = VisionParityComparator.ReferenceSnapshot(
            status = "READY",
            boardRoi = android.grid.boardRoi,
            gridMethod = android.method,
            gridConfidence = android.gridConfidence,
            xBoundaries = android.grid.xBoundaries.copyOf(),
            yBoundaries = android.grid.yBoundaries.copyOf(),
            cellBoxes = android.grid.cells(),
            cells = cells,
            unknownCount = 0,
            gate = "PASS",
        )
        val report = VisionParityComparator.compare(android, ref)
        assertThat(report.status).isEqualTo("COMPARED")
        assertThat(report.roi!!.match).isTrue()
        assertThat(report.boundaries!!.xMaxAbsDelta).isEqualTo(0f)
        assertThat(report.cellBoxes!!.meanIoU).isWithin(1e-5f).of(1f)
        assertThat(report.color!!.matchRate).isEqualTo(1f)
        assertThat(report.shape!!.matchRate).isEqualTo(1f)
        assertThat(report.special!!.matchRate).isEqualTo(1f)
        assertThat(report.occlusion!!.matchRate).isEqualTo(1f)
        assertThat(report.unknownCount!!.absDelta).isEqualTo(0)
        assertThat(report.gate!!.match).isTrue()
    }

    @Test
    fun colorMismatch_lowersOnlyColorMetric() {
        val android = syntheticResult()
        val cells = mutableListOf<CellVision>()
        for (r in 0 until 7) for (c in 0 until 7) {
            val base = android.board.get(r, c)
            cells.add(
                if (r == 0 && c == 0) base.copy(color = TileColor.R) else base
            )
        }
        val ref = VisionParityComparator.ReferenceSnapshot(
            status = "READY",
            boardRoi = android.grid.boardRoi,
            xBoundaries = android.grid.xBoundaries.copyOf(),
            yBoundaries = android.grid.yBoundaries.copyOf(),
            cellBoxes = android.grid.cells(),
            cells = cells,
            unknownCount = 0,
            gate = "PASS",
        )
        val report = VisionParityComparator.compare(android, ref)
        assertThat(report.color!!.matched).isEqualTo(48)
        assertThat(report.shape!!.matchRate).isEqualTo(1f)
        assertThat(report.gate!!.match).isTrue()
    }

    @Test
    fun gateMismatch_reportedSeparately() {
        val android = syntheticResult(gatePass = true)
        val ref = VisionParityComparator.ReferenceSnapshot(
            status = "READY",
            gate = "HOLD",
            unknownCount = 0,
        )
        val report = VisionParityComparator.compare(android, ref)
        assertThat(report.gate!!.match).isFalse()
        assertThat(report.gate!!.androidGate).isEqualTo("PASS")
        assertThat(report.gate!!.referenceGate).isEqualTo("HOLD")
    }

    @Test
    fun exporter_containsRequiredSchemaFields() {
        val android = syntheticResult()
        val json = VisionResultExporter.toJson(
            result = android,
            imageWidth = 1080,
            imageHeight = 1920,
            letterboxRoi = ContentRoi(0, 100, 1080, 1820),
        )
        listOf(
            "imageWidth", "imageHeight", "letterboxRoi", "boardRoi", "roiOffset",
            "gridMethod", "gridConfidence", "xBoundaries", "yBoundaries",
            "cellBoxes", "cells", "unknownCount", "gate", "validation",
            "centerX", "centerY", "finalTile",
        ).forEach { key ->
            assertThat(json).contains("\"$key\"")
        }
        assertThat(json).contains("\"ANDROID_EXPORT\"")
        assertThat(json).contains("PARITY_VERIFIED=NO")
    }

    @Test
    fun exporter_emitsFortyNineCells() {
        val android = syntheticResult()
        val json = VisionResultExporter.toJson(android, 100, 100, null)
        val count = Regex("\"row\":").findAll(json).count()
        // 49 cellBoxes + 49 cells = 98
        assertThat(count).isEqualTo(98)
    }
}
