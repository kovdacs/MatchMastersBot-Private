package com.match3vision.analyzer.capture

/**
 * A capture buffer may be analyzed only after a real pixel copy succeeded.
 *
 * A failed [android.graphics.Bitmap.getPixels], a recycled bitmap, a missing
 * frame, or a length that does not match the frame is [FAILURE_CLASS].
 * The caller must not substitute a zero-filled array and must not call
 * [com.match3vision.analyzer.vision.VisionPipeline] on that failure.
 * A genuinely copied black frame is a real observation and is admitted.
 */
object CaptureBufferGate {
    const val FAILURE_CLASS = "CAPTURE_INVALID"

    data class Admission<T>(
        val admitted: Boolean,
        val value: T?,
        val reason: String,
    )

    /**
     * @return null when the buffer may be analyzed. Otherwise a HOLD reason
     * that already starts with [FAILURE_CLASS].
     */
    fun refusalReason(
        copySucceeded: Boolean,
        width: Int,
        height: Int,
        bufferLength: Int,
    ): String? {
        if (width <= 0 || height <= 0) {
            return "$FAILURE_CLASS — missing or invalid frame size ${width}x$height"
        }
        if (!copySucceeded) {
            return "$FAILURE_CLASS — pixel copy failed; zero substitute was not analyzed"
        }
        val expected = width.toLong() * height.toLong()
        if (expected <= 0L || expected > Int.MAX_VALUE.toLong() || bufferLength.toLong() != expected) {
            return "$FAILURE_CLASS — buffer length $bufferLength != ${width}x$height"
        }
        return null
    }

    /**
     * [analyze] is the VisionPipeline entry. It is not called when [refusalReason]
     * is non-null, even if [bufferLength] equals width times height.
     */
    fun <T> analyzeIfAdmitted(
        copySucceeded: Boolean,
        width: Int,
        height: Int,
        bufferLength: Int,
        analyze: () -> T,
    ): Admission<T> {
        val refusal = refusalReason(copySucceeded, width, height, bufferLength)
        if (refusal != null) {
            return Admission(admitted = false, value = null, reason = refusal)
        }
        return Admission(admitted = true, value = analyze(), reason = "copied ${width}x$height")
    }
}
