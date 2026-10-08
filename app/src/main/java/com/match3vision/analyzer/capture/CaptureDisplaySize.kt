package com.match3vision.analyzer.capture

/**
 * Pixel size of the MediaProjection virtual display.
 *
 * Prefer [android.view.WindowManager.getMaximumWindowMetrics], the same
 * bounds the isolated touch test uses as physical screen pixels (API 30+).
 * Fall back to real display metrics on API 29 or when maximum bounds are 0.
 *
 * The bitmap is then 1:1 with that pixel grid. [com.match3vision.analyzer.input.FrameScreenCoordinatePolicy]
 * sends cell centers through unchanged. A phone is still required to prove
 * the accessibility service uses that same grid.
 */
object CaptureDisplaySize {
    data class Px(
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        val source: String,
    )

    fun choose(
        maximumWindowWidth: Int,
        maximumWindowHeight: Int,
        realWidth: Int,
        realHeight: Int,
        densityDpi: Int,
    ): Px {
        if (maximumWindowWidth > 0 && maximumWindowHeight > 0) {
            return Px(
                width = maximumWindowWidth,
                height = maximumWindowHeight,
                densityDpi = densityDpi,
                source = SOURCE_MAXIMUM_WINDOW,
            )
        }
        if (realWidth > 0 && realHeight > 0) {
            return Px(
                width = realWidth,
                height = realHeight,
                densityDpi = densityDpi,
                source = SOURCE_REAL_METRICS,
            )
        }
        return Px(width = 0, height = 0, densityDpi = densityDpi, source = SOURCE_UNKNOWN)
    }

    const val SOURCE_MAXIMUM_WINDOW = "maximumWindowMetrics"
    const val SOURCE_REAL_METRICS = "realMetrics"
    const val SOURCE_UNKNOWN = "unknown"
}
