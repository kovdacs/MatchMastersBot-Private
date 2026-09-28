package com.match3vision.analyzer.capture

import android.graphics.Bitmap

/**
 * One captured screen frame for the analyzer pipeline.
 *
 * @property width Frame width in pixels.
 * @property height Frame height in pixels.
 * @property timestampMs Capture time ([System.currentTimeMillis]).
 * @property bitmap ARGB_8888 bitmap (packed from MediaProjection RGBA_8888) owned by the caller after emission.
 * @property contentRoi Optional content rectangle after letterbox detection.
 */
data class CaptureFrame(
    val width: Int,
    val height: Int,
    val timestampMs: Long,
    val bitmap: Bitmap,
    val contentRoi: ContentRoi? = null,
) {
    val contentWidth: Int
        get() = contentRoi?.width() ?: width

    val contentHeight: Int
        get() = contentRoi?.height() ?: height
}
