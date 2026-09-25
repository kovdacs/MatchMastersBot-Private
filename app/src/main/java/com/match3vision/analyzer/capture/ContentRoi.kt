package com.match3vision.analyzer.capture

/**
 * Axis-aligned content region inside a captured frame (pixel coordinates).
 * Pure data — usable from JVM unit tests without Robolectric.
 */
data class ContentRoi(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(right >= left) { "right < left" }
        require(bottom >= top) { "bottom < top" }
    }

    fun width(): Int = right - left
    fun height(): Int = bottom - top

    companion object {
        fun full(width: Int, height: Int) = ContentRoi(0, 0, width, height)
    }
}
