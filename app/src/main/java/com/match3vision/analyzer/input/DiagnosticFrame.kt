package com.match3vision.analyzer.input

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Downscaled copy of the analyzed frame with the ROI and grid drawn on it.
 *
 * Pure pixel work. It does not run vision and it does not change a gate.
 * A missing or inconsistent buffer is reported as not exported. A blank
 * image is not invented in its place.
 */
object DiagnosticFrame {
    const val MAX_EDGE = 480
    const val OVERLAY = 0xFFFF00FF.toInt()
    const val STATUS_EXPORTED = "EXPORTED"
    const val STATUS_NOT_EXPORTED = "NOT EXPORTED"

    data class Export(
        val status: String,
        val reason: String,
        val png: ByteArray?,
        val width: Int,
        val height: Int,
        val pixels: IntArray?,
    )

    fun render(
        pixels: IntArray?,
        width: Int,
        height: Int,
        roiLeft: Int,
        roiTop: Int,
        roiRight: Int,
        roiBottom: Int,
        xBoundaries: FloatArray?,
        yBoundaries: FloatArray?,
        refusal: String? = null,
    ): Export {
        if (!refusal.isNullOrBlank()) {
            return missing(refusal)
        }
        if (pixels == null) {
            return missing("NOT EXPORTED — frame pixels were not available")
        }
        if (width <= 0 || height <= 0 || pixels.size != width * height) {
            return missing(
                "NOT EXPORTED — frame pixels are not a $width x $height buffer " +
                    "(length=${pixels.size})",
            )
        }
        val longest = maxOf(width, height)
        val scale = minOf(1f, MAX_EDGE.toFloat() / longest.toFloat())
        val dw = maxOf(1, (width * scale).toInt())
        val dh = maxOf(1, (height * scale).toInt())
        val out = IntArray(dw * dh)
        for (y in 0 until dh) {
            val sy = (y.toLong() * height / dh).toInt().coerceIn(0, height - 1)
            for (x in 0 until dw) {
                val sx = (x.toLong() * width / dw).toInt().coerceIn(0, width - 1)
                out[y * dw + x] = pixels[sy * width + sx]
            }
        }
        fun sx(frameX: Int): Int =
            (frameX.toLong() * dw / width).toInt().coerceIn(0, dw - 1)
        fun sy(frameY: Int): Int =
            (frameY.toLong() * dh / height).toInt().coerceIn(0, dh - 1)
        if (roiRight > roiLeft && roiBottom > roiTop) {
            strokeRect(out, dw, dh, sx(roiLeft), sy(roiTop), sx(roiRight - 1), sy(roiBottom - 1))
        }
        xBoundaries?.forEach { boundary ->
            val x = sx(boundary.toInt())
            for (y in 0 until dh) out[y * dw + x] = OVERLAY
        }
        yBoundaries?.forEach { boundary ->
            val y = sy(boundary.toInt()).coerceIn(0, dh - 1)
            for (x in 0 until dw) out[y * dw + x] = OVERLAY
        }
        return Export(
            status = STATUS_EXPORTED,
            reason = "downscaled ${width}x$height to ${dw}x$dh with ROI and grid overlay",
            png = PngEncoder.encode(out, dw, dh),
            width = dw,
            height = dh,
            pixels = out,
        )
    }

    private fun missing(reason: String) = Export(
        status = STATUS_NOT_EXPORTED,
        reason = reason,
        png = null,
        width = 0,
        height = 0,
        pixels = null,
    )

    private fun strokeRect(pixels: IntArray, w: Int, h: Int, l: Int, t: Int, r: Int, b: Int) {
        val left = l.coerceIn(0, w - 1)
        val right = r.coerceIn(0, w - 1)
        val top = t.coerceIn(0, h - 1)
        val bottom = b.coerceIn(0, h - 1)
        for (x in left..right) {
            pixels[top * w + x] = OVERLAY
            pixels[bottom * w + x] = OVERLAY
        }
        for (y in top..bottom) {
            pixels[y * w + left] = OVERLAY
            pixels[y * w + right] = OVERLAY
        }
    }
}

/**
 * Mean luminance of a frame copy. This is a diagnostic label only.
 * It is not a vision threshold and it is not read by the PASS/HOLD gate.
 */
object DiagnosticLuminance {
    const val BLACK_MEAN = 8.0

    data class Summary(val mean: Double, val sampleCount: Int, val text: String)

    fun measure(pixels: IntArray?): Summary {
        if (pixels == null || pixels.isEmpty()) {
            return Summary(
                mean = -1.0,
                sampleCount = 0,
                text = "not measured — frame pixels were not available",
            )
        }
        var sum = 0.0
        var n = 0
        var i = 0
        val step = if (pixels.size > 200_000) 16 else 1
        while (i < pixels.size) {
            val p = pixels[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            sum += 0.299 * r + 0.587 * g + 0.114 * b
            n++
            i += step
        }
        val mean = sum / n
        val text = if (mean < BLACK_MEAN) {
            "BLACK FRAME meanLuma=%.2f".format(mean)
        } else {
            "not a black frame meanLuma=%.2f".format(mean)
        }
        return Summary(mean, n, text)
    }
}

/** Minimal RGB PNG. Used so a phone share sheet can open the overlay. */
object PngEncoder {
    fun encode(argb: IntArray, width: Int, height: Int): ByteArray {
        val raw = ByteArrayOutputStream(width * height * 3 + height)
        for (y in 0 until height) {
            raw.write(0)
            for (x in 0 until width) {
                val p = argb[y * width + x]
                raw.write((p ushr 16) and 0xFF)
                raw.write((p ushr 8) and 0xFF)
                raw.write(p and 0xFF)
            }
        }
        val deflater = Deflater()
        deflater.setInput(raw.toByteArray())
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n > 0) compressed.write(buf, 0, n)
        }
        deflater.end()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        writeChunk(out, "IHDR", ihdr(width, height))
        writeChunk(out, "IDAT", compressed.toByteArray())
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun ihdr(width: Int, height: Int): ByteArray {
        val data = ByteArray(13)
        putInt(data, 0, width)
        putInt(data, 4, height)
        data[8] = 8
        data[9] = 2
        return data
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val len = ByteArray(4)
        putInt(len, 0, data.size)
        out.write(len)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        val crcBytes = ByteArray(4)
        putInt(crcBytes, 0, crc.value.toInt())
        out.write(crcBytes)
    }

    private fun putInt(dest: ByteArray, offset: Int, value: Int) {
        dest[offset] = (value ushr 24).toByte()
        dest[offset + 1] = (value ushr 16).toByte()
        dest[offset + 2] = (value ushr 8).toByte()
        dest[offset + 3] = value.toByte()
    }
}
