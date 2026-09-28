package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import com.match3vision.analyzer.moves.Move
import com.match3vision.analyzer.vision.GridGeometry
import org.junit.Test

/**
 * Coordinate mapping audit (P1): ROI → cell centers → GestureSpec screen coords.
 *
 * Mapping uses **frame pixel** coordinates from [GridGeometry] (same space as
 * MediaProjection bitmap). Letterbox ContentRoi is already baked into boardRoi
 * by BoardFinder; density/dp is NOT applied (gestures are in raw screen px).
 * Status/nav bars and bubble overlays must stay off the board ROI so centers
 * land on gems — documented in docs/COORDINATE_MAPPING_AUDIT.md.
 */
class TouchCoordinateMapperAuditTest {

    private val mapper = TouchCoordinateMapper(swipeDurationMs = 120L)

    @Test
    fun cellCenters_matchEvenSplitRoi() {
        val roi = ContentRoi(20, 1206, 1060, 2246)
        val grid = GridGeometry.evenSplit(roi, confidence = 0.99f)
        val move = Move(r1 = 0, c1 = 0, r2 = 0, c2 = 1)
        val g = mapper.toGesture(move, grid)
        val from = grid.cellBox(0, 0)
        val to = grid.cellBox(0, 1)
        assertThat(g.startX).isWithin(0.01f).of(from.centerX())
        assertThat(g.startY).isWithin(0.01f).of(from.centerY())
        assertThat(g.endX).isWithin(0.01f).of(to.centerX())
        assertThat(g.endY).isWithin(0.01f).of(to.centerY())
        assertThat(g.startX).isAtLeast(roi.left.toFloat())
        assertThat(g.endX).isAtMost(roi.right.toFloat())
        assertThat(g.startY).isAtLeast(roi.top.toFloat())
        assertThat(g.endY).isAtMost(roi.bottom.toFloat())
    }

    @Test
    fun letterboxedRoi_centersStayInsideBoard() {
        // Simulated letterbox: black bars top/bottom, board in middle band.
        val roi = ContentRoi(0, 400, 1080, 1480)
        val grid = GridGeometry.evenSplit(roi, confidence = 0.99f)
        for (r in 0 until 7) {
            for (c in 0 until 6) {
                val g = mapper.toGesture(Move(r, c, r, c + 1), grid)
                assertThat(g.startX).isAtLeast(roi.left.toFloat())
                assertThat(g.endX).isAtMost(roi.right.toFloat())
                assertThat(g.startY).isAtLeast(roi.top.toFloat())
                assertThat(g.endY).isAtMost(roi.bottom.toFloat())
            }
        }
    }

    @Test
    fun nonMonotonicGrid_throws() {
        val bad = floatArrayOf(0f, 10f, 20f, 30f, 40f, 50f, 60f, 50f) // last not monotonic
        val y = floatArrayOf(0f, 10f, 20f, 30f, 40f, 50f, 60f, 70f)
        val grid = GridGeometry(
            xBoundaries = bad,
            yBoundaries = y,
            method = com.match3vision.analyzer.vision.GridMethod.EVEN_SPLIT,
            confidence = 0.99f,
            boardRoi = ContentRoi(0, 0, 70, 70),
        )
        try {
            mapper.toGesture(Move(0, 0, 0, 1), grid)
            throw AssertionError("expected require failure")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message!!.lowercase()).contains("monotonic")
        }
    }

    @Test
    fun noHardcodedPvpBoardPixels() {
        // Mapper has no device-literal constants — only grid centers + duration.
        val roi = ContentRoi(100, 200, 800, 900)
        val grid = GridGeometry.evenSplit(roi, 0.99f)
        val g = mapper.toGesture(Move(3, 3, 3, 4), grid)
        assertThat(g.durationMs).isEqualTo(120L)
        assertThat(g.startX).isNotEqualTo(0f)
        assertThat(g.startY).isNotEqualTo(0f)
    }
}
