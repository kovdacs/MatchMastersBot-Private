package com.match3vision.analyzer.input

/**
 * Production [InputGestureExecutor] backed by [MatchMastersAccessibilityService].
 *
 * Ready when the system AccessibilityService is connected and reports
 * [MatchMastersAccessibilityService.canDispatchGestures] (CAPABILITY_CAN_PERFORM_GESTURES).
 * Auto-play still layers [InputEnableSwitch]; isolated [AutomaticTouchTest]
 * dispatches after explicit bubble «TESZT ÉRINTÉS» without Vision gates.
 *
 * [dispatch] awaits [GestureResultCallback] when called off the main thread so
 * SUCCESS means onCompleted (not merely scheduled).
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
            return InputDispatchResult.Failed(
                "AccessibilityService cannot dispatch gestures (${svc.diagnose()})",
            )
        }
        return svc.dispatchGesture(gesture, awaitCompletion = true)
    }
}
