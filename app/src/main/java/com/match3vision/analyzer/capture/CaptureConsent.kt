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

    /**
     * A consent token is single-use. While a projection is already running,
     * a second getMediaProjection with the same result Intent is refused.
     * Returns null when a new projection may be created.
     */
    fun reuseRefusal(alreadyCapturing: Boolean, hasResultData: Boolean): String? = when {
        !hasResultData -> "missing MediaProjection consent token"
        alreadyCapturing ->
            "refusing to reuse MediaProjection consent token while capture is running"
        else -> null
    }
}

/**
 * Static requirements for the capture service. Runtime behaviour on a phone
 * is not claimed here.
 */
object MediaProjectionStartup {
    /** API 29+ must call startForeground with the mediaProjection type before getMediaProjection. */
    fun requiresForegroundServiceFirst(sdkInt: Int): Boolean = sdkInt >= 29

    const val FOREGROUND_SERVICE_TYPE = "mediaProjection"

    /**
     * Sideloaded apps on Android 13+ cannot enable this accessibility service
     * until the user allows restricted settings for the app.
     */
    const val RESTRICTED_SETTINGS_STEP =
        "Android 13+ sideload: Settings → Apps → this app → allow restricted settings, " +
            "then enable the accessibility service. Enabling can be wiped by uninstall."
}
