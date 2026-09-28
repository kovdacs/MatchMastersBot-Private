package com.match3vision.analyzer.input

/**
 * Production [InputGestureExecutor] backed by [MatchMastersAccessibilityService].
 *
 * Ready when the system AccessibilityService is connected
 * ([MatchMastersAccessibilityService.isConnected] / instance non-null).
 * Auto-play still layers [InputEnableSwitch]; isolated [AutomaticTouchTest]
 * dispatches after explicit bubble «TESZT ÉRINTÉS» without Vision gates.
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
