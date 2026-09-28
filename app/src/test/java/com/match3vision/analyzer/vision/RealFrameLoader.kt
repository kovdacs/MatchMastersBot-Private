package com.match3vision.analyzer.vision

import java.io.File
import java.io.InputStream

/**
 * JVM JPEG/PNG → ARGB [IntArray] loader for REAL_FRAME / fixture harness tests.
 *
 * Decodes via [javax.imageio.ImageIO] at **runtime** (CI Temurin 17 has ImageIO).
 * AGP unit-test **compile** classpath stubs omit `java.awt` / `javax.imageio`, so
 * calls go through reflection — not an Android Bitmap path.
 *
 * REAL_FIXTURE: loads `real_frames/pvp_board.jpg` (primary) plus optional secondary
 * overlay/FX/volume frames from test resources.
 */
object RealFrameLoader {

    const val PVP_BOARD_RESOURCE = "real_frames/pvp_board.jpg"
    const val PVP_BOARD_SHOWDOWN_RESOURCE = "real_frames/pvp_board_showdown_overlay.jpg"
    const val PVP_BOARD_ACTIVATE_FX_RESOURCE = "real_frames/pvp_board_activate_fx.jpg"
    const val PVP_BOARD_MID_VOLUME_RESOURCE = "real_frames/pvp_board_mid_volume.jpg"
    const val REAL_FRAME_MISSING = "REAL_FRAME_MISSING"

    val SECONDARY_RESOURCES: List<Pair<String, String>> = listOf(
        "SHOWDOWN_OVERLAY" to PVP_BOARD_SHOWDOWN_RESOURCE,
        "ACTIVATE_FX" to PVP_BOARD_ACTIVATE_FX_RESOURCE,
        "MID_VOLUME" to PVP_BOARD_MID_VOLUME_RESOURCE,
    )

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
        // Reflection: AGP unit-test compile classpath lacks javax.imageio / java.awt.
        val imageIO = Class.forName("javax.imageio.ImageIO")
        val readMethod = imageIO.getMethod("read", InputStream::class.java)
        val buffered = readMethod.invoke(null, input)
            ?: error("ImageIO failed to decode: $sourceLabel")
        return fromBufferedImageReflect(buffered, sourceLabel)
    }

    private fun fromBufferedImageReflect(image: Any, sourceLabel: String): LoadedFrame {
        val w = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val h = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val getRgb = image.javaClass.getMethod(
            "getRGB",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = getRgb.invoke(image, x, y) as Int
            }
        }
        return LoadedFrame(pixels, w, h, sourceLabel)
    }
}
