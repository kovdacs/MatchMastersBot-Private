package com.match3vision.analyzer.capture

/**
 * Configuration for screen-capture rate and letterbox detection.
 * Phase 1: analyzer-only; no touch / automation settings.
 */
data class CaptureConfig(
    val targetFps: Int = DEFAULT_TARGET_FPS,
    val letterboxLumaThreshold: Int = DEFAULT_LETTERBOX_LUMA,
    val letterboxBarMinRatio: Float = DEFAULT_BAR_MIN_RATIO,
) {
    init {
        require(targetFps in MIN_FPS..MAX_FPS) {
            "targetFps must be in $MIN_FPS..$MAX_FPS (got $targetFps)"
        }
        require(letterboxLumaThreshold in 0..255) {
            "letterboxLumaThreshold must be 0..255"
        }
        require(letterboxBarMinRatio in 0f..0.45f) {
            "letterboxBarMinRatio must be 0..0.45"
        }
    }

    /** Milliseconds between frames for the target FPS. */
    val frameIntervalMs: Long
        get() = 1000L / targetFps.coerceAtLeast(1)

    companion object {
        const val DEFAULT_TARGET_FPS: Int = 5
        const val MIN_FPS: Int = 1
        const val MAX_FPS: Int = 15
        const val DEFAULT_LETTERBOX_LUMA: Int = 16
        const val DEFAULT_BAR_MIN_RATIO: Float = 0.02f

        /**
         * Clamps [fps] into [MIN_FPS]..[MAX_FPS].
         */
        fun clampFps(fps: Int): Int = fps.coerceIn(MIN_FPS, MAX_FPS)

        /**
         * Builds a config with FPS clamped to the allowed range.
         */
        fun withClampedFps(
            fps: Int,
            letterboxLumaThreshold: Int = DEFAULT_LETTERBOX_LUMA,
            letterboxBarMinRatio: Float = DEFAULT_BAR_MIN_RATIO,
        ): CaptureConfig = CaptureConfig(
            targetFps = clampFps(fps),
            letterboxLumaThreshold = letterboxLumaThreshold,
            letterboxBarMinRatio = letterboxBarMinRatio,
        )
    }
}
