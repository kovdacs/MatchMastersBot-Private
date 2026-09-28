package com.match3vision.analyzer.input

/**
 * Pure startup readiness gate (unit-testable, no Android deps).
 *
 * **Settings flag ≠ connected.** Autoplay may enter RUNNING / input-ready only when
 * [MatchMastersAccessibilityService] is actually connected at runtime.
 * A settings-enabled but not-yet-connected service must block RUNNING clearly
 * (ACCESSIBILITY: DISCONNECTED) — never silent HOLD.
 */
object StartupReadinessGate {

    enum class A11yStatus {
        CONNECTED,
        DISCONNECTED,
    }

    enum class LastDispatch {
        NONE,
        SUCCESS,
        FAILED,
    }

    data class Decision(
        val a11yStatus: A11yStatus,
        /** True only when runtime a11y is connected and capture/overlay allow start. */
        val canEnterRunning: Boolean,
        /**
         * True when input channel may dispatch: runtime a11y connected AND
         * [InputEnableSwitch] enabled. Settings-only never yields YES.
         */
        val inputReady: Boolean,
        val blockReason: String?,
    )

    /**
     * @param runtimeConnected [MatchMastersAccessibilityService.isConnected]
     * @param settingsEnabled optional Settings.Secure listing (informational only)
     * @param captureReady MediaProjection / CaptureService ready
     * @param overlayReady SYSTEM_ALERT_WINDOW granted
     * @param inputSwitchEnabled [InputEnableSwitch.isEnabled]
     */
    fun evaluate(
        runtimeConnected: Boolean,
        settingsEnabled: Boolean = false,
        captureReady: Boolean = true,
        overlayReady: Boolean = true,
        inputSwitchEnabled: Boolean = false,
    ): Decision {
        val a11y = if (runtimeConnected) A11yStatus.CONNECTED else A11yStatus.DISCONNECTED
        if (!runtimeConnected) {
            val reason = if (settingsEnabled) {
                "ACCESSIBILITY: DISCONNECTED (settings on, service not connected)"
            } else {
                "ACCESSIBILITY: DISCONNECTED"
            }
            return Decision(
                a11yStatus = a11y,
                canEnterRunning = false,
                inputReady = false,
                blockReason = reason,
            )
        }
        if (!captureReady) {
            return Decision(
                a11yStatus = a11y,
                canEnterRunning = false,
                inputReady = false,
                blockReason = "CAPTURE: OFF",
            )
        }
        if (!overlayReady) {
            return Decision(
                a11yStatus = a11y,
                canEnterRunning = false,
                inputReady = false,
                blockReason = "OVERLAY not ready",
            )
        }
        return Decision(
            a11yStatus = a11y,
            canEnterRunning = true,
            inputReady = inputSwitchEnabled,
            blockReason = null,
        )
    }
}
