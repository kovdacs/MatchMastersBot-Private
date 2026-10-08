package com.match3vision.analyzer.capture

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Pixel size of the physical display, read from window metrics.
 *
 * This is not a capture bitmap. Callers must not pass the frame width and
 * height through [source] [SOURCE_FRAME]. Equal numbers still do not prove
 * that gesture coordinates and capture pixels share an origin.
 */
data class ScreenMeasurement(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int = 0,
    /** [android.view.Surface.ROTATION_0] and friends, or [ROTATION_UNKNOWN]. */
    val rotation: Int = ROTATION_UNKNOWN,
    val source: String,
) {
    val valid: Boolean
        get() = widthPx > 0 && heightPx > 0 &&
            source.isNotBlank() &&
            !source.equals(SOURCE_FRAME, ignoreCase = true) &&
            !source.equals(SOURCE_UNAVAILABLE, ignoreCase = true)

    companion object {
        const val ROTATION_UNKNOWN = -1
        const val SOURCE_MAXIMUM_WINDOW = "maximumWindowMetrics"
        const val SOURCE_CURRENT_WINDOW = "currentWindowMetrics"
        const val SOURCE_REAL_METRICS = "realMetrics"
        const val SOURCE_UNAVAILABLE = "unavailable"
        const val SOURCE_FRAME = "frame"

        fun unavailable(reason: String = SOURCE_UNAVAILABLE): ScreenMeasurement = ScreenMeasurement(
            widthPx = 0,
            heightPx = 0,
            source = reason.ifBlank { SOURCE_UNAVAILABLE },
        )
    }
}

fun interface ScreenMetricsSource {
    fun measure(): ScreenMeasurement
}

/**
 * Chooses an independent screen size from WindowManager readings.
 * The captured bitmap is not an input.
 */
object IndependentScreenMetrics {
    fun choose(
        maximumWindowWidth: Int,
        maximumWindowHeight: Int,
        currentWindowWidth: Int,
        currentWindowHeight: Int,
        realWidth: Int,
        realHeight: Int,
        densityDpi: Int,
        rotation: Int,
    ): ScreenMeasurement {
        if (maximumWindowWidth > 0 && maximumWindowHeight > 0) {
            return ScreenMeasurement(
                widthPx = maximumWindowWidth,
                heightPx = maximumWindowHeight,
                densityDpi = densityDpi,
                rotation = rotation,
                source = ScreenMeasurement.SOURCE_MAXIMUM_WINDOW,
            )
        }
        if (currentWindowWidth > 0 && currentWindowHeight > 0) {
            return ScreenMeasurement(
                widthPx = currentWindowWidth,
                heightPx = currentWindowHeight,
                densityDpi = densityDpi,
                rotation = rotation,
                source = ScreenMeasurement.SOURCE_CURRENT_WINDOW,
            )
        }
        if (realWidth > 0 && realHeight > 0) {
            return ScreenMeasurement(
                widthPx = realWidth,
                heightPx = realHeight,
                densityDpi = densityDpi,
                rotation = rotation,
                source = ScreenMeasurement.SOURCE_REAL_METRICS,
            )
        }
        return ScreenMeasurement.unavailable()
    }
}

/**
 * Live display read. JVM unit tests do not construct this; they pass a
 * [ScreenMeasurement] from [IndependentScreenMetrics.choose] or a fixture.
 * What remains unverified without a device: that WindowManager on the
 * accessibility service returns the same pixel grid dispatchGesture uses.
 */
class AndroidScreenMetrics(
    private val windowManager: WindowManager?,
    private val fallbackDensityDpi: Int = 0,
) : ScreenMetricsSource {
    constructor(context: Context) : this(
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager,
        fallbackDensityDpi = context.resources.displayMetrics.densityDpi,
    )

    override fun measure(): ScreenMeasurement {
        val wm = windowManager ?: return ScreenMeasurement.unavailable("window-manager-missing")
        var maxW = 0
        var maxH = 0
        var curW = 0
        var curH = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val max = wm.maximumWindowMetrics.bounds
            maxW = max.width()
            maxH = max.height()
            val cur = wm.currentWindowMetrics.bounds
            curW = cur.width()
            curH = cur.height()
        }
        val real = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(real)
        @Suppress("DEPRECATION")
        val rotation = wm.defaultDisplay.rotation
        val density = when {
            real.densityDpi > 0 -> real.densityDpi
            fallbackDensityDpi > 0 -> fallbackDensityDpi
            else -> 0
        }
        return IndependentScreenMetrics.choose(
            maximumWindowWidth = maxW,
            maximumWindowHeight = maxH,
            currentWindowWidth = curW,
            currentWindowHeight = curH,
            realWidth = real.widthPixels,
            realHeight = real.heightPixels,
            densityDpi = density,
            rotation = rotation,
        )
    }
}
