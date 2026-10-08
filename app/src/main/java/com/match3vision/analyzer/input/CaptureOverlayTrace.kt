package com.match3vision.analyzer.input

/**
 * Last overlay facts the bubble loop measured. Export reads this.
 * It does not move the overlay and it does not dispatch.
 */
object CaptureOverlayTrace {
    @Volatile
    var collapsed: Boolean = false

    @Volatile
    var overlayRect: String = "unknown"

    @Volatile
    var gateResult: String = "not evaluated"

    @Volatile
    var skippedAfterCollapse: Int = 0

    @Volatile
    var collapseWallMs: Long = 0L

    @Volatile
    var analyzedFrameTimestampMs: Long = 0L

    @Volatile
    var gestureStatus: String = "NOT CREATED"

    @Volatile
    var dispatchState: String = "NOT STARTED"

    fun noteSkip() {
        skippedAfterCollapse += 1
    }

    fun clear() {
        collapsed = false
        overlayRect = "unknown"
        gateResult = "not evaluated"
        skippedAfterCollapse = 0
        collapseWallMs = 0L
        analyzedFrameTimestampMs = 0L
        gestureStatus = "NOT CREATED"
        dispatchState = "NOT STARTED"
    }
}
