package com.match3vision.analyzer.input

/**
 * The live `outsideTouches=1` could only come from the bubble overlay.
 * [com.match3vision.analyzer.overlay.FloatingBubbleService] is the only caller,
 * and it listens for [android.view.MotionEvent.ACTION_OUTSIDE]. Accessibility
 * window and content events are ignored by the service. Nothing in the board
 * diff, settle, or HUD path increments the counter.
 *
 * A counted touch has to be that overlay event, from a touchscreen finger.
 * A window-content event, a windows-changed event, or a board-change note
 * never counts, even if someone forwards it here.
 */
object OverlayOutsideTouch {
    const val WINDOW = "bubble"
    const val ACTION = "ACTION_OUTSIDE"
    const val TOUCHSCREEN = "touchscreen"
    const val FINGER = "finger"

    fun counts(
        eventType: String,
        sourceWindow: String,
        inputSource: String,
        toolType: String,
    ): Boolean = eventType == ACTION &&
        sourceWindow == WINDOW &&
        inputSource == TOUCHSCREEN &&
        toolType == FINGER
}
