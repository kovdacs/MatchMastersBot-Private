package com.match3vision.analyzer.capture

/**
 * How MediaProjection consent is requested.
 *
 * API 34+ `createScreenCaptureIntent()` lets the user share a single app.
 * The easy choice is this analyzer. After it is backgrounded, the capture is
 * not Match Masters, Vision stays HOLD, and no gesture is dispatched.
 * API 34+ therefore requests the entire default display.
 */
object CaptureConsent {
    const val ENTIRE_DISPLAY_MIN_SDK = 34

    enum class Mode {
        /** Pre-34 chooser. No entire-display config API. */
        LEGACY_CHOOSER,
        /** [android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay]. */
        ENTIRE_DEFAULT_DISPLAY,
    }

    fun modeForSdk(sdkInt: Int): Mode =
        if (sdkInt >= ENTIRE_DISPLAY_MIN_SDK) Mode.ENTIRE_DEFAULT_DISPLAY else Mode.LEGACY_CHOOSER
}
