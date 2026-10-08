package com.match3vision.analyzer.input

/**
 * Frame freshness clock.
 *
 * Age uses [android.os.SystemClock.elapsedRealtime] (monotonic). A wall-clock
 * jump (NTP, user change) must not make a stale frame look fresh or a fresh
 * frame look stale.
 *
 * [com.match3vision.analyzer.capture.CaptureFrame.timestampMs] stays wall time
 * for the bubble's human-readable timestamp. Ordering a post-dispatch frame
 * ("later than dispatch") prefers the same monotonic clock when both samples
 * exist.
 *
 * Threshold is [GestureFailSafe.MAX_FRAME_AGE_MS] (3000). Age equal to the
 * threshold is still fresh (`>`). Missing or backwards monotonic samples are
 * fail-closed (treated as stale).
 */
object FrameClock {
    const val NAME = "SystemClock.elapsedRealtime"
    const val STALE_AFTER_MS = GestureFailSafe.MAX_FRAME_AGE_MS

    fun ageMs(capturedElapsedRealtimeMs: Long, nowElapsedRealtimeMs: Long): Long {
        if (capturedElapsedRealtimeMs <= 0L || nowElapsedRealtimeMs <= 0L ||
            nowElapsedRealtimeMs < capturedElapsedRealtimeMs
        ) {
            return STALE_AFTER_MS + 1L
        }
        return nowElapsedRealtimeMs - capturedElapsedRealtimeMs
    }

    fun isStale(ageMs: Long): Boolean = ageMs > STALE_AFTER_MS

    /** 0 when the Android clock stub is unavailable (JVM unit tests). */
    fun tryElapsed(): Long = try {
        android.os.SystemClock.elapsedRealtime()
    } catch (_: Throwable) {
        0L
    }
}
