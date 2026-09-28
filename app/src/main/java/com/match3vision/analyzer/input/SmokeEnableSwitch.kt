package com.match3vision.analyzer.input

/**
 * Explicit ENABLE for **controlled one-step smoke test**.
 *
 * Separate from [InputEnableSwitch]:
 * - App start: both DISABLED.
 * - Smoke dispatch requires this switch **and** [InputEnableSwitch] (layered).
 * - Enabling smoke does **not** start a continuous / unattended loop.
 * - [OneStepSmokeController] enforces max 1 auto swipe per session.
 */
class SmokeEnableSwitch(
    initiallyEnabled: Boolean = false,
) {
    @Volatile
    private var enabled: Boolean = initiallyEnabled

    fun isEnabled(): Boolean = enabled

    /** Explicit user/operator action. Default path never calls this with true. */
    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun disable() {
        enabled = false
    }

    companion object {
        fun disabledByDefault(): SmokeEnableSwitch = SmokeEnableSwitch(initiallyEnabled = false)
    }
}
