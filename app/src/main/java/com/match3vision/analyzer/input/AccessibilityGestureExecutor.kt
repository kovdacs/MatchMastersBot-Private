package com.match3vision.analyzer.input

/**
 * Production [InputGestureExecutor] backed by [MatchMastersAccessibilityService].
 *
 * Enable gate (all required):
 * 1. User enables the system AccessibilityService for this app.
 * 2. [InputEnableSwitch] explicitly set to enabled (default DISABLED).
 * 3. Service instance connected ([MatchMastersAccessibilityService.isConnected]).
 *
 * Without (1)+(3), [isReady] is false → AutomaticInputEngine HOLDs (no input).
 */
class AccessibilityGestureExecutor(
    private val serviceProvider: () -> MatchMastersAccessibilityService? =
        { MatchMastersAccessibilityService.instanceOrNull() },
) : InputGestureExecutor {

    override fun isReady(): Boolean {
        val svc = serviceProvider() ?: return false
        return svc.canDispatchGestures()
    }

    override fun dispatch(gesture: GestureSpec): InputDispatchResult {
        val svc = serviceProvider()
            ?: return InputDispatchResult.Failed("AccessibilityService not connected")
        if (!svc.canDispatchGestures()) {
            return InputDispatchResult.Failed("AccessibilityService cannot dispatch gestures")
        }
        return svc.dispatchGesture(gesture)
    }
}
