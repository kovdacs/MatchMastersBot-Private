package com.match3vision.analyzer.vision

import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import javax.imageio.ImageIO

/**
 * JVM JPEG/PNG → ARGB [IntArray] loader for REAL_FRAME / fixture harness tests.
 *
 * Uses [javax.imageio.ImageIO] (available on CI Temurin 17). Not an Android Bitmap path.
 *
 * REAL_FIXTURE: loads `real_frames/pvp_board.jpg` from test resources or filesystem
 * when present. Absence is expected until a real Match Masters capture is checked in.
 */
object RealFrameLoader {

    const val PVP_BOARD_RESOURCE = "real_frames/pvp_board.jpg"
    const val REAL_FRAME_MISSING = "REAL_FRAME_MISSING"

    data class LoadedFrame(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val source: String,
    )

    fun resourceExists(resourcePath: String = PVP_BOARD_RESOURCE): Boolean =
        Thread.currentThread().contextClassLoader.getResource(resourcePath) != null

    fun loadFromResource(resourcePath: String = PVP_BOARD_RESOURCE): LoadedFrame? {
        val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(resourcePath)
            ?: return null
        stream.use { return decode(it, "classpath:$resourcePath") }
    }

    fun loadFromFile(file: File): LoadedFrame? {
        if (!file.isFile) return null
        return decode(file.inputStream(), file.absolutePath)
    }

    fun decode(input: InputStream, sourceLabel: String): LoadedFrame {
        val image: BufferedImage = ImageIO.read(input)
            ?: error("ImageIO failed to decode: $sourceLabel")
        return fromBufferedImage(image, sourceLabel)
    }

    fun fromBufferedImage(image: BufferedImage, sourceLabel: String): LoadedFrame {
        val w = image.width
        val h = image.height
        val pixels = IntArray(w * h)
        // Normalize to TYPE_INT_ARGB sampling
        for (y in 0 until h) {
            for (x in 0 until w) {
                val rgb = image.getRGB(x, y) // ARGB int
                pixels[y * w + x] = rgb
            }
        }
        return LoadedFrame(pixels, w, h, sourceLabel)
    }
}
