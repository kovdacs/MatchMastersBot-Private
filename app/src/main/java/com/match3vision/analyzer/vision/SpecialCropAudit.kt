package com.match3vision.analyzer.vision

/**
 * Records every special-detector crop and the ones whose unguarded arrow
 * band would have indexed outside the cell. The clamp in [SpecialDetector]
 * still refuses that index. This object only names the caller.
 */
object SpecialCropAudit {
    data class Rejection(
        val source: String,
        val width: Int,
        val height: Int,
        val unguardedIndex: Int,
        val bufferShort: Boolean,
    )

    private val lock = Any()
    private val sizes = ArrayList<String>()
    private val rejections = ArrayList<Rejection>()

    fun clear() {
        synchronized(lock) {
            sizes.clear()
            rejections.clear()
        }
    }

    fun observe(source: String, width: Int, height: Int, bufferLength: Int) {
        val index = SpecialDetector.unguardedArrowIndex(width, height)
        val short = width > 0 && height > 0 &&
            width.toLong() * height.toLong() > bufferLength.toLong()
        val bad = index < 0 || short
        synchronized(lock) {
            sizes.add("${width}x$height")
            if (bad) {
                rejections.add(Rejection(source, width, height, index, short))
            }
        }
    }

    fun sizesText(): String = synchronized(lock) {
        if (sizes.isEmpty()) "none" else sizes.joinToString(",")
    }

    fun rejectedCount(): Int = synchronized(lock) { rejections.size }

    fun originText(): String = synchronized(lock) {
        if (rejections.isEmpty()) {
            "none"
        } else {
            rejections.joinToString("; ") {
                "${it.source} ${it.width}x${it.height} unguardedIndex=${it.unguardedIndex}" +
                    if (it.bufferShort) " bufferShort" else ""
            }
        }
    }
}
