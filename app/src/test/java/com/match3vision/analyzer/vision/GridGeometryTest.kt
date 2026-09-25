package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import org.junit.Test

class GridGeometryTest {

    @Test
    fun evenSplit_has7x7CellsAnd8Boundaries() {
        val roi = ContentRoi(10, 20, 150, 160)
        val grid = GridGeometry.evenSplit(roi, confidence = 0.8f)
        assertThat(grid.xBoundaries).hasLength(8)
        assertThat(grid.yBoundaries).hasLength(8)
        assertThat(grid.method).isEqualTo(GridMethod.EVEN_SPLIT)
        assertThat(grid.cells()).hasSize(49)
        assertThat(grid.validate()).isTrue()
    }

    @Test
    fun boundaries_areMonotonic() {
        val grid = GridGeometry.evenSplit(ContentRoi(0, 0, 70, 70))
        assertThat(grid.isMonotonic()).isTrue()
        assertThat(GridGeometry.isStrictlyIncreasing(grid.xBoundaries)).isTrue()
        assertThat(GridGeometry.isStrictlyIncreasing(grid.yBoundaries)).isTrue()
    }

    @Test
    fun cellCenters_lieInsideBoardRoi() {
        val roi = ContentRoi(5, 10, 75, 80)
        val grid = GridGeometry.evenSplit(roi)
        for (r in 0 until 7) {
            for (c in 0 until 7) {
                val cell = grid.cellBox(r, c)
                assertThat(cell.centerX()).isAtLeast(roi.left.toFloat())
                assertThat(cell.centerX()).isAtMost(roi.right.toFloat())
                assertThat(cell.centerY()).isAtLeast(roi.top.toFloat())
                assertThat(cell.centerY()).isAtMost(roi.bottom.toFloat())
            }
        }
    }

    @Test
    fun cellBox_matchesBoundaries() {
        val grid = GridGeometry.evenSplit(ContentRoi(0, 0, 70, 140))
        val cell = grid.cellBox(2, 3)
        assertThat(cell.row).isEqualTo(2)
        assertThat(cell.col).isEqualTo(3)
        assertThat(cell.left).isEqualTo(grid.xBoundaries[3])
        assertThat(cell.right).isEqualTo(grid.xBoundaries[4])
        assertThat(cell.top).isEqualTo(grid.yBoundaries[2])
        assertThat(cell.bottom).isEqualTo(grid.yBoundaries[3])
        // Non-square cells allowed (board 70×140)
        assertThat(cell.width()).isWithin(0.01f).of(10f)
        assertThat(cell.height()).isWithin(0.01f).of(20f)
    }

    @Test
    fun nonMonotonic_failsValidate() {
        val x = floatArrayOf(0f, 10f, 20f, 30f, 25f, 50f, 60f, 70f) // dip
        val y = floatArrayOf(0f, 10f, 20f, 30f, 40f, 50f, 60f, 70f)
        val grid = GridGeometry(x, y, GridMethod.PROJECTION, 0.9f, ContentRoi(0, 0, 70, 70))
        assertThat(grid.isMonotonic()).isFalse()
        assertThat(grid.validate()).isFalse()
    }

    @Test
    fun highSpacingVariance_failsSpacingOk() {
        val x = floatArrayOf(0f, 5f, 10f, 15f, 20f, 25f, 30f, 100f)
        val y = floatArrayOf(0f, 10f, 20f, 30f, 40f, 50f, 60f, 70f)
        val grid = GridGeometry(x, y, GridMethod.PROJECTION, 0.5f, ContentRoi(0, 0, 100, 70))
        assertThat(grid.spacingOk(0.12f)).isFalse()
    }
}
