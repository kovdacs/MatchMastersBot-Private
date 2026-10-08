package com.match3vision.analyzer.capture

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.vision.GridMethod
import com.match3vision.analyzer.vision.RealFrameLoader
import com.match3vision.analyzer.vision.VisionPipeline
import com.match3vision.analyzer.vision.VisionResult
import java.nio.ByteBuffer
import org.junit.Test

/**
 * JPEG fixture pixels packed as a padded RGBA_8888 plane, then
 * [RgbaBufferUnpack] and the same [VisionPipeline] as the JPEG path.
 */
class RgbaCaptureParityTest {

    @Test
    fun paddedRgba_matchesJpegFixture_geometryGateAndChannels() {
        val frame = RealFrameLoader.loadFromResource()
            ?: error("pvp_board.jpg missing")
        val red = findRedDominant(frame.pixels, frame.width)
        val packed = packRgba(
            pixels = frame.pixels,
            width = frame.width,
            height = frame.height,
            rowPadding = 64,
            swapRedBlue = false,
        )
        val unpacked = RgbaBufferUnpack.unpack(
            buffer = packed.buffer,
            width = frame.width,
            height = frame.height,
            rowStride = packed.rowStride,
            pixelStride = 4,
        ) ?: error("unpack returned null for a valid padded buffer")
        assertThat(unpacked.size).isEqualTo(frame.pixels.size)
        assertThat(unpacked[red.index]).isEqualTo(frame.pixels[red.index])
        val got = unpacked[red.index]
        assertThat((got ushr 16) and 0xFF).isEqualTo(red.r)
        assertThat((got ushr 8) and 0xFF).isEqualTo(red.g)
        assertThat(got and 0xFF).isEqualTo(red.b)
        assertThat(red.r).isGreaterThan(red.b + 40)
        assertThat(unpacked.contentEquals(frame.pixels)).isTrue()

        val jpeg = analyze(frame.pixels, frame.width, frame.height)
        val rgba = analyze(unpacked, frame.width, frame.height)
        assertSameVision(jpeg, rgba)
        println(
            "RGBA_PARITY gate=${if (rgba.validation.isPass) "PASS" else "HOLD"} " +
                "method=${rgba.method} grid=${rgba.gridConfidence} " +
                "roi=${rgba.grid.boardRoi} redPixel=(${red.x},${red.y}) " +
                "R=${red.r} G=${red.g} B=${red.b} rowStride=${packed.rowStride}",
        )
    }

    @Test
    fun rbSwap_changesChannelOrder_andBoardInterpretation() {
        val frame = RealFrameLoader.loadFromResource()
            ?: error("pvp_board.jpg missing")
        val red = findRedDominant(frame.pixels, frame.width)
        val swapped = packRgba(
            pixels = frame.pixels,
            width = frame.width,
            height = frame.height,
            rowPadding = 64,
            swapRedBlue = true,
        )
        val unpacked = RgbaBufferUnpack.unpack(
            buffer = swapped.buffer,
            width = frame.width,
            height = frame.height,
            rowStride = swapped.rowStride,
            pixelStride = 4,
        ) ?: error("unpack returned null")
        val pixel = unpacked[red.index]
        val unpackedR = (pixel ushr 16) and 0xFF
        val unpackedB = pixel and 0xFF
        assertThat(unpackedR).isEqualTo(red.b)
        assertThat(unpackedB).isEqualTo(red.r)
        assertThat(unpackedR).isNotEqualTo(red.r)
        val correct = analyze(frame.pixels, frame.width, frame.height)
        val wrong = analyze(unpacked, frame.width, frame.height)
        assertThat(colorLabels(wrong)).isNotEqualTo(colorLabels(correct))
        println(
            "RGBA_RB_SWAP redPixel=(${red.x},${red.y}) jpegR=${red.r} jpegB=${red.b} " +
                "unpackedR=$unpackedR unpackedB=$unpackedB " +
                "correctGate=${if (correct.validation.isPass) "PASS" else "HOLD"} " +
                "swappedGate=${if (wrong.validation.isPass) "PASS" else "HOLD"} " +
                "labelsDiffer=true",
        )
    }

    private fun analyze(pixels: IntArray, width: Int, height: Int): VisionResult =
        VisionPipeline().analyze(
            pixels,
            width,
            height,
            ContentRoi.full(width, height),
        )

    private fun assertSameVision(jpeg: VisionResult, rgba: VisionResult) {
        assertThat(rgba.validation.isPass).isEqualTo(jpeg.validation.isPass)
        assertThat(rgba.validation.isPass).isTrue()
        assertThat(rgba.method).isEqualTo(GridMethod.PROJECTION)
        assertThat(jpeg.method).isEqualTo(GridMethod.PROJECTION)
        assertThat(rgba.grid.boardRoi).isEqualTo(jpeg.grid.boardRoi)
        assertThat(rgba.unknownCount).isEqualTo(jpeg.unknownCount)
        assertThat(rgba.gridConfidence).isWithin(1e-5f).of(jpeg.gridConfidence)
        assertThat(rgba.boardConfidence).isWithin(1e-5f).of(jpeg.boardConfidence)
        assertThat(rgba.grid.xBoundaries.size).isEqualTo(jpeg.grid.xBoundaries.size)
        for (i in jpeg.grid.xBoundaries.indices) {
            assertThat(rgba.grid.xBoundaries[i]).isWithin(0.01f).of(jpeg.grid.xBoundaries[i])
            assertThat(rgba.grid.yBoundaries[i]).isWithin(0.01f).of(jpeg.grid.yBoundaries[i])
        }
        assertThat(colorLabels(rgba)).isEqualTo(colorLabels(jpeg))
    }

    private fun colorLabels(result: VisionResult): String = buildString {
        for (r in 0 until 7) {
            for (c in 0 until 7) {
                val cell = result.board.get(r, c)
                append(if (cell.isUnknown) "UNK" else cell.color.name)
                append(',')
            }
        }
    }

    private data class Packed(val buffer: ByteBuffer, val rowStride: Int)

    private fun packRgba(
        pixels: IntArray,
        width: Int,
        height: Int,
        rowPadding: Int,
        swapRedBlue: Boolean,
    ): Packed {
        val pixelStride = 4
        val rowStride = width * pixelStride + rowPadding
        val bytes = ByteArray((height - 1) * rowStride + width * pixelStride)
        for (y in 0 until height) {
            val row = y * rowStride
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val r = ((p ushr 16) and 0xFF).toByte()
                val g = ((p ushr 8) and 0xFF).toByte()
                val b = (p and 0xFF).toByte()
                val o = row + x * pixelStride
                if (swapRedBlue) {
                    bytes[o] = b
                    bytes[o + 1] = g
                    bytes[o + 2] = r
                } else {
                    bytes[o] = r
                    bytes[o + 1] = g
                    bytes[o + 2] = b
                }
                bytes[o + 3] = 0xFF.toByte()
            }
            if (rowPadding > 0 && y < height - 1) {
                for (pad in 0 until rowPadding) bytes[row + width * pixelStride + pad] = 0x5A
            }
        }
        return Packed(ByteBuffer.wrap(bytes), rowStride)
    }

    private data class RedPixel(val index: Int, val x: Int, val y: Int, val r: Int, val g: Int, val b: Int)

    private fun findRedDominant(pixels: IntArray, width: Int): RedPixel {
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            if (r > 180 && r > b + 40 && g < 140) {
                return RedPixel(i, i % width, i / width, r, g, b)
            }
        }
        error("fixture has no red-dominant pixel; the R/B assertion would be vacuous")
    }
}
