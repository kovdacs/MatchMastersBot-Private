package com.match3vision.analyzer.input

/**
 * What [MatchMastersAccessibilityService] exposes to the executor.
 * Tests substitute a channel that counts [dispatchGesture] calls.
 * Production passes the live service instance.
 */
interface AccessibilityGestureChannel {
    fun canDispatchGestures(): Boolean
    fun diagnose(): String
    fun dispatchGesture(
        gesture: GestureSpec,
        awaitCompletion: Boolean = true,
        timeoutMs: Long = MatchMastersAccessibilityService.GESTURE_CALLBACK_TIMEOUT_MS,
    ): InputDispatchResult
}

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
 * [dispatchChecked] re-runs [DispatchRecheck] immediately before [dispatchGesture].
 */
class AccessibilityGestureExecutor(
    private val serviceProvider: () -> AccessibilityGestureChannel? =
        { MatchMastersAccessibilityService.instanceOrNull() },
) : InputGestureExecutor {

    override fun isReady(): Boolean {
        val svc = serviceProvider() ?: return false
        return svc.canDispatchGestures()
    }

    /**
     * TOCTOU gate. Call this from the production engine, not [dispatch] alone.
     * A failing permit returns before [AccessibilityGestureChannel.dispatchGesture].
     */
    fun dispatchChecked(permit: DispatchPermit): InputDispatchResult {
        // Re-read the live channel. A plan-time "connected" flag is not enough.
        val live = serviceProvider()?.canDispatchGestures() == true
        val again = DispatchRecheck.evaluate(
            permit.copy(a11yConnected = permit.a11yConnected && live),
        )
        if (!again.allow) {
            return InputDispatchResult.Failed(
                "TOCTOU recheck blocked dispatchGesture: ${again.reason}",
            )
        }
        return dispatch(permit.gesture)
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
