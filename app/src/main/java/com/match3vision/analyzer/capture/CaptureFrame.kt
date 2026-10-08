package com.match3vision.analyzer.capture

import android.graphics.Bitmap
import com.match3vision.analyzer.input.FrameClock

/**
 * One captured screen frame for the analyzer pipeline.
 *
 * @property width Frame width in pixels.
 * @property height Frame height in pixels.
 * @property timestampMs Wall-clock capture time ([System.currentTimeMillis]). Display only.
 * @property elapsedRealtimeMs Monotonic [android.os.SystemClock.elapsedRealtime] at capture.
 *   Freshness uses this. 0 means the sample was not recorded (age falls back to wall time).
 * @property bitmap ARGB_8888 bitmap (packed from MediaProjection RGBA_8888) owned by the caller after emission.
 * @property contentRoi Optional content rectangle after letterbox detection.
 * @property sequence Monotonic capture sequence (for FrameSequenceGate NEW/SAME/OLD).
 */
data class CaptureFrame(
    val width: Int,
    val height: Int,
    val timestampMs: Long,
    val bitmap: Bitmap,
    val contentRoi: ContentRoi? = null,
    val sequence: Long = 0L,
    val elapsedRealtimeMs: Long = 0L,
) {
    val contentWidth: Int
        get() = contentRoi?.width() ?: width

    val contentHeight: Int
        get() = contentRoi?.height() ?: height

    /** Stable identity for SAME-frame detection (object identity of this emission). */
    fun identityHash(): Long = System.identityHashCode(this).toLong() and 0xffffffffL

    fun toSequenceId(): FrameSequenceGate.FrameId = FrameSequenceGate.FrameId(
        sequence = sequence,
        identity = identityHash(),
        timestampMs = timestampMs,
    )

    /**
     * Age for the staleness gate. Prefers monotonic [elapsedRealtimeMs].
     * Wall clock is used only when the monotonic sample was not recorded.
     */
    fun ageMs(
        nowWallMs: Long = System.currentTimeMillis(),
        nowElapsedMs: Long = FrameClock.tryElapsed(),
    ): Long {
        if (elapsedRealtimeMs > 0L) {
            val nowMono = if (nowElapsedMs > 0L) nowElapsedMs else FrameClock.tryElapsed()
            if (nowMono > 0L) return FrameClock.ageMs(elapsedRealtimeMs, nowMono)
        }
        return (nowWallMs - timestampMs).coerceAtLeast(0L)
    }
}
