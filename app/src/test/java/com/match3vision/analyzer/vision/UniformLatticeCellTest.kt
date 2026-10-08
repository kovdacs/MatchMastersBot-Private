package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.match3vision.analyzer.capture.ContentRoi
import org.junit.Test

/**
 * Phone 0.24.7.2 held on unknownCount 2. The same cells, index 0 (0,0) and
 * index 13 (1,6), were unknown on every frame. Row pitches were
 * 148, 147, 174, 150, 157, 126, 169 inside ROI top 1167, fourteen pixels
 * above the earlier passing top. This test snaps that geometry onto one
 * lattice period and requires a real PASS. Gates stay 0.98 / 0.95 / unk≤1.
 */
class UniformLatticeCellTest {

    @Test
    fun gatesStayClosed() {
        assertThat(VisionThresholds.MIN_GRID_CONFIDENCE).isEqualTo(0.98f)
        assertThat(VisionThresholds.MIN_BOARD_CONFIDENCE).isEqualTo(0.95f)
        assertThat(VisionThresholds.MAX_UNKNOWN_COUNT).isEqualTo(1)
    }

    @Test
    fun phoneRowPitches_snapToPeriod150_origin14() {
        val picked = floatArrayOf(0f, 148f, 295f, 469f, 619f, 776f, 902f, 1071f)
        val energy = FloatArray(1071)
        val origin = 14
        val period = 150
        for (i in 0..7) {
            val y = origin + i * period
            if (y in energy.indices) energy[y] = 10f
        }
        for (boundary in picked) {
            val y = boundary.toInt()
            if (y in energy.indices) energy[y] = maxOf(energy[y], 14f)
        }
        val fit = BoardFinder.uniformPitchFromEnergy(picked, energy)
        assertThat(fit.applied).isTrue()
        assertThat(fit.spreadPx).isGreaterThan(BoardFinder.UNIFORM_PITCH_SPREAD_PX)
        assertThat(fit.period).isEqualTo(150)
        assertThat(kotlin.math.abs(fit.origin - 14)).isAtMost(1)
        val pitches = FloatArray(7) { i -> fit.bounds[i + 1] - fit.bounds[i] }
        for (pitch in pitches) {
            assertThat(pitch).isWithin(0.1f).of(150f)
        }
    }

    @Test
    fun evenPitches_areNotReplaced() {
        val picked = FloatArray(8) { i -> i * 150f }
        val energy = FloatArray(1050) { 1f }
        val fit = BoardFinder.uniformPitchFromEnergy(picked, energy)
        assertThat(fit.applied).isFalse()
        assertThat(fit.bounds).isSameInstanceAs(picked)
    }

    @Test
    fun unevenPhoneGrid_classifiesCornerAndFarEdge_asTheirPaintedColors() {
        val frame = phoneRoiFrame()
        val result = VisionPipeline().analyze(
            frame.pixels,
            frame.width,
            frame.height,
            ContentRoi.full(frame.width, frame.height),
        )
        val pickedY = splitBounds(result.diagnostics["projPickedY"])
        val pickedPitches = FloatArray(7) { i -> pickedY[i + 1] - pickedY[i] }
        val pickedSpread = pickedPitches.max() - pickedPitches.min()
        assertWithMessage(result.diagnostics.toString())
            .that(pickedSpread)
            .isGreaterThan(BoardFinder.UNIFORM_PITCH_SPREAD_PX)

        val cell0 = result.board.get(0, 0)
        val cell13 = result.board.get(1, 6)
        VisionDiagnostics.assertOrDump(
            result,
            result.validation.isPass &&
                !cell0.isUnknown && cell0.color == TileColor.R &&
                !cell13.isUnknown && cell13.color == TileColor.B &&
                result.unknownCount <= VisionThresholds.MAX_UNKNOWN_COUNT &&
                result.gridConfidence >= VisionThresholds.MIN_GRID_CONFIDENCE &&
                result.boardConfidence >= VisionThresholds.MIN_BOARD_CONFIDENCE,
            "index 0 and 13 must classify from the uniform lattice, not stay UNK. " +
                "uniform=${result.diagnostics["uniformSnap"]} " +
                "y=${result.grid.yBoundaries.joinToString(",") { it.toInt().toString() }} " +
                "cell0=${cell0.color}/${cell0.isUnknown} cell13=${cell13.color}/${cell13.isUnknown}",
        )
        assertThat(result.diagnostics["uniformSnap"]).contains("y:period=")
        assertThat(result.diagnostics["playfieldSnap"]).isEqualTo("gutter_lattice")
        assertThat(result.method).isEqualTo(GridMethod.PROJECTION)
        val pitches = FloatArray(7) { i ->
            result.grid.yBoundaries[i + 1] - result.grid.yBoundaries[i]
        }
        val pitch = pitches[0]
        assertThat(pitch).isGreaterThan(140f)
        assertThat(pitch).isLessThan(160f)
        for (p in pitches) {
            assertThat(p).isWithin(1f).of(pitch)
        }
    }

    private fun splitBounds(text: String?): FloatArray {
        check(text != null && text.isNotEmpty()) { "missing picked bounds" }
        return text.split(",").map { it.toFloat() }.toFloatArray()
    }

    /**
     * 1080×2400 phone frame. Dark-blue gutters sit on a 150 px lattice at
     * y=1181. Brighter lines at the uneven phone pitches pull projection off
     * those gutters. The lattice path then snaps the cuts back onto one period.
     */
    private fun phoneRoiFrame(): Frame {
        val width = 1080
        val height = 2400
        val header = PixelMath.rgb(140, 140, 150)
        val playfield = PixelMath.rgb(18, 16, 62)
        val shine = PixelMath.rgb(255, 255, 255)
        val pixels = IntArray(width * height) { header }
        val yOrigin = 1181
        val xOrigin = 18
        val pitch = 150
        val inset = 12
        fill(pixels, width, height, 0, yOrigin, width, yOrigin + 7 * pitch, playfield)
        for (row in 0 until 7) {
            for (col in 0 until 7) {
                val color = when {
                    row == 0 && col == 0 -> SyntheticFrames.COLOR_R
                    row == 1 && col == 6 -> SyntheticFrames.COLOR_B
                    (col + row) % 2 == 0 -> SyntheticFrames.COLOR_Y
                    else -> SyntheticFrames.COLOR_G
                }
                val x0 = xOrigin + col * pitch + inset
                val y0 = yOrigin + row * pitch + inset
                val x1 = xOrigin + (col + 1) * pitch - inset
                val y1 = yOrigin + (row + 1) * pitch - inset
                fill(pixels, width, height, x0, y0, x1, y1, color)
            }
        }
        for (i in 0..7) {
            hLine(pixels, width, height, yOrigin + i * pitch, playfield)
            vLine(pixels, width, height, xOrigin + i * pitch, playfield)
        }
        // ±18 px from the 150 px ideals: inside the projection clamp, spread 72 px.
        val badY = intArrayOf(132, 318, 432, 618, 732, 918)
        val badX = intArrayOf(132, 318, 432, 618, 732, 918)
        for (dy in badY) hLine(pixels, width, height, yOrigin + dy, shine)
        for (dx in badX) vLine(pixels, width, height, xOrigin + dx, shine)
        return Frame(pixels, width, height)
    }

    private fun fill(
        pixels: IntArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        color: Int,
    ) {
        for (y in top until bottom) {
            if (y !in 0 until height) continue
            for (x in left until right) {
                if (x !in 0 until width) continue
                pixels[y * width + x] = color
            }
        }
    }

    private fun hLine(pixels: IntArray, width: Int, height: Int, y: Int, color: Int) {
        for (dy in 0..1) {
            val yy = y + dy
            if (yy !in 0 until height) continue
            for (x in 0 until width) pixels[yy * width + x] = color
        }
    }

    private fun vLine(pixels: IntArray, width: Int, height: Int, x: Int, color: Int) {
        for (dx in 0..1) {
            val xx = x + dx
            if (xx !in 0 until width) continue
            for (y in 0 until height) pixels[y * width + xx] = color
        }
    }

    private data class Frame(val pixels: IntArray, val width: Int, val height: Int)
}
