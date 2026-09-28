package com.match3vision.analyzer.input

/**
 * INPUT ENABLE/DISABLE safety switch.
 *
 * **Default DISABLED.** Never auto-enables after install.
 * Explicit [setEnabled](true) is required before AutomaticInputEngine may dispatch.
 * DecisionEngine / MoveAnalysisEngine remain read-only regardless of this flag;
 * only [AutomaticInputEngine] consults it.
 */
class InputEnableSwitch(
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
        /** Factory for production — always starts DISABLED. */
        fun disabledByDefault(): InputEnableSwitch = InputEnableSwitch(initiallyEnabled = false)
    }
}
