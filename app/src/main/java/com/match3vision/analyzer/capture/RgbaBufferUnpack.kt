package com.match3vision.analyzer.capture

import java.nio.ByteBuffer

/**
 * ImageReader RGBA_8888 plane → packed ARGB pixels.
 *
 * [rowStride] may be larger than width * pixelStride (OEM padding). Padding
 * bytes are skipped. A short stride or a short buffer returns null so the
 * capture path does not publish a shifted frame.
 */
object RgbaBufferUnpack {
    fun unpack(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): IntArray? {
        if (width <= 0 || height <= 0) return null
        if (pixelStride < 4) return null
        val minRow = width * pixelStride
        if (rowStride < minRow) return null
        val needed = (height - 1) * rowStride + minRow
        if (needed < 0 || buffer.capacity() < needed) return null
        val argb = IntArray(width * height)
        var dst = 0
        for (row in 0 until height) {
            var pos = row * rowStride
            for (col in 0 until width) {
                val r = buffer.get(pos).toInt() and 0xFF
                val g = buffer.get(pos + 1).toInt() and 0xFF
                val b = buffer.get(pos + 2).toInt() and 0xFF
                val a = buffer.get(pos + 3).toInt() and 0xFF
                argb[dst++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                pos += pixelStride
            }
        }
        return argb
    }
}
