package com.match3vision.analyzer.input

/**
 * Screen-space gesture derived from MoveEvaluation + recognized grid cell centers.
 * Coordinates are frame/screen pixels — never hardcoded board positions.
 */
data class GestureSpec(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val durationMs: Long = InputThresholds.SWIPE_DURATION_MS,
) {
    init {
        require(durationMs > 0L) { "durationMs must be > 0" }
        require(startX.isFinite() && startY.isFinite() && endX.isFinite() && endY.isFinite()) {
            "gesture coords must be finite"
        }
    }

    val isSwipe: Boolean
        get() = startX != endX || startY != endY

    companion object {
        fun tap(x: Float, y: Float, durationMs: Long = 50L) =
            GestureSpec(x, y, x, y, durationMs)
    }
}

/** Outcome of attempting to dispatch a gesture to the OS. */
sealed class InputDispatchResult {
    /**
     * The channel accepted the gesture.
     * [callbackCompleted] is true only after GestureResultCallback.onCompleted.
     * Scheduling alone leaves it false, so verification must not start.
     */
    data class Dispatched(
        val gesture: GestureSpec,
        val callbackCompleted: Boolean = false,
    ) : InputDispatchResult()

    data class Failed(val reason: String) : InputDispatchResult()
}
