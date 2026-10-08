package com.match3vision.analyzer.input

/**
 * Timing facts of the production verify call site.
 *
 * These constants name the waits that already exist. They are not a new
 * settle policy and they do not change [InputThresholds].
 *
 * Frame time is receipt time, not image content time.
 * [com.match3vision.analyzer.capture.ScreenCaptureManager] sets
 * [com.match3vision.analyzer.capture.CaptureFrame.timestampMs] from
 * `System.currentTimeMillis()` when `ImageReader` delivers the buffer, and
 * [com.match3vision.analyzer.capture.CaptureFrame.elapsedRealtimeMs] from
 * [FrameClock] (`SystemClock.elapsedRealtime`) at that same receipt.
 * `Image.getTimestamp()` is not read. Neither value is the exposure time.
 *
 * The gesture duration is [InputThresholds.SWIPE_DURATION_MS] (120). The
 * accessibility callback is awaited inside `dispatchGesture`. Verification
 * does not start at schedule time and does not start when the callback
 * returns. The bubble then waits [POST_DISPATCH_WAIT_MS], which is
 * [InputThresholds.ANIMATION_WAIT_MS] (650). There is no additional settle
 * delay. After that wait it polls up to [NEW_FRAME_POLL_BUDGET_MS] for a
 * frame [com.match3vision.analyzer.capture.FrameSequenceGate] accepts as
 * newer than the pre-dispatch frame. [VerifyObservation.derive] then requires
 * that frame's monotonic receipt time to be strictly later than the
 * elapsedRealtime sample taken when `dispatchGesture` returned, and fresh
 * under the existing 3000 ms gate.
 *
 * Board change is not a pixel diff of the swipe. It is the post-dispatch
 * [com.match3vision.analyzer.vision.VisionResult] board hash compared with the
 * pre-dispatch hash, plus [SwapRegionCheck] on the intended swap cells of
 * that same pair of results.
 */
object VerifyTiming {
    const val SWIPE_DURATION_MS: Long = InputThresholds.SWIPE_DURATION_MS
    const val POST_DISPATCH_WAIT_MS: Long = InputThresholds.ANIMATION_WAIT_MS
    const val NEW_FRAME_POLL_BUDGET_MS: Long = 2_500L
    const val NEW_FRAME_POLL_STEP_MS: Long = 100L

    const val FRAME_TIMESTAMP_MEANING: String =
        "wall-clock receipt time (System.currentTimeMillis at ImageReader delivery), not image content time"
    const val FRAME_ELAPSED_MEANING: String =
        "monotonic receipt time (SystemClock.elapsedRealtime at ImageReader delivery), not image content time"
    const val BOARD_CHANGE_OBSERVATION: String =
        "post-dispatch VisionResult board content hash versus the pre-dispatch hash, " +
            "plus SwapRegionCheck of the intended swap cells"

    const val SCREEN_METRICS_CONTEXT: String =
        "FloatingBubbleService. The service Context supplies WINDOW_SERVICE " +
            "(maximumWindowMetrics, then currentWindowMetrics, then getRealMetrics) " +
            "and resources.displayMetrics.densityDpi as a density fallback. " +
            "It is not MainActivity, not the capture bitmap, and not frame width/height."

    const val DISPATCH_SCREEN_SOURCE: String =
        "AccessibilityGestureExecutor.dispatchChecked re-reads " +
            "LiveDispatchProbe.screen(), which ProductionLiveReaders installs as " +
            "AndroidScreenMetrics(FloatingBubbleService).measure(). " +
            "The captured frame is not a screen source."
}
