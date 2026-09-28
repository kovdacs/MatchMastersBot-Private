package com.match3vision.analyzer.input

/**
 * Structured auto-play / gesture dispatch trace for logcat + bubble diagnostics.
 * Exact stop reason is retained when the loop stalls.
 */
object AutoPlayTrace {

    const val TAG_VISION_PASS = "VISION PASS"
    const val TAG_MOVE_SELECTED = "MOVE SELECTED"
    const val TAG_INPUT_READY = "INPUT READY"
    const val TAG_GESTURE_CREATED = "GESTURE CREATED"
    const val TAG_DISPATCH_START = "DISPATCH START"
    const val TAG_DISPATCH_RESULT = "DISPATCH RESULT"
    const val TAG_STOP_REASON = "STOP REASON"

    @Volatile
    var lastStopReason: String? = null
        private set

    private val ring = ArrayDeque<String>(64)

    fun log(tag: String, detail: String = "") {
        val line = if (detail.isBlank()) tag else "$tag — $detail"
        synchronized(ring) {
            ring.addLast(line)
            while (ring.size > 80) ring.removeFirst()
        }
        if (tag == TAG_STOP_REASON || detail.contains("STOP", ignoreCase = true)) {
            lastStopReason = line
        }
        try {
            timber.log.Timber.i("AUTOPLAY_TRACE: %s", line)
        } catch (_: Throwable) {
            // Unit tests / no Timber tree — ignore.
        }
    }

    fun markStop(reason: String) {
        lastStopReason = reason
        log(TAG_STOP_REASON, reason)
    }

    fun recentLines(max: Int = 24): List<String> = synchronized(ring) {
        ring.toList().takeLast(max)
    }

    fun clear() {
        synchronized(ring) { ring.clear() }
        lastStopReason = null
    }
}
