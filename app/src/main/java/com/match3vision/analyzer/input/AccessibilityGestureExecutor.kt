package com.match3vision.analyzer.input

import com.match3vision.analyzer.capture.ScreenMeasurement

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
 * [dispatchChecked] re-reads input, stop, capture, and screen size, then
 * [DispatchRecheck], immediately before [dispatchGesture].
 * [dispatch] is refused. The manual TESZT ÉRINTÉS path is [dispatchManualTest].
 */
class AccessibilityGestureExecutor(
    private val serviceProvider: () -> AccessibilityGestureChannel? =
        { MatchMastersAccessibilityService.instanceOrNull() },
    /** Monotonic clock at the moment of dispatch. JVM tests inject this. */
    private val nowElapsedMs: () -> Long = { FrameClock.tryElapsed() },
    /** Live facts. Unavailable reads fail closed. */
    private val liveProbe: LiveDispatchProbe = LiveDispatchProbe.unavailable(),
) : InputGestureExecutor {

    override fun isReady(): Boolean {
        val svc = serviceProvider() ?: return false
        return svc.canDispatchGestures()
    }

    /**
     * Last-moment gate. Production auto-play must call this, not [dispatch].
     * A failing read returns before [AccessibilityGestureChannel.dispatchGesture].
     */
    fun dispatchChecked(permit: DispatchPermit): InputDispatchResult {
        val screen = readScreen()
        if (screen == null || !screen.valid) {
            return blocked(
                "screen measurement unavailable (${screen?.source ?: ScreenMeasurement.SOURCE_UNAVAILABLE})",
            )
        }
        if (screen.source.equals(ScreenMeasurement.SOURCE_FRAME, ignoreCase = true)) {
            return blocked("screen measurement derived from the capture frame")
        }
        val liveInput = readOrNull { liveProbe.inputEnabled() }
        val liveStopped = readOrNull { liveProbe.stopped() }
        val liveCapture = readOrNull { liveProbe.captureReady() }
        if (liveInput == null) return blocked("live input-enabled state unavailable")
        if (liveStopped == null) return blocked("live stop state unavailable")
        if (liveCapture == null) return blocked("live capture state unavailable")
        if (liveStopped) return blocked("STOP — live stop state")
        if (!liveInput) return blocked("input disabled (live)")
        if (!liveCapture) return blocked("CAPTURE: OFF (live)")
        if (permit.screenWidth != screen.widthPx || permit.screenHeight != screen.heightPx) {
            return blocked(
                "screen changed during analysis plan=${permit.screenWidth}x${permit.screenHeight} " +
                    "live=${screen.widthPx}x${screen.heightPx}",
            )
        }
        val space = FrameScreenCoordinatePolicy.assess(
            frameWidth = permit.frameWidth,
            frameHeight = permit.frameHeight,
            screenWidth = screen.widthPx,
            screenHeight = screen.heightPx,
        )
        if (space.mapping != FrameScreenCoordinatePolicy.Mapping.IDENTITY_FRAME_PIXELS) {
            return blocked(space.reason)
        }
        // Matching size is not origin proof. Checks continue; alignment stays unproven.
        if (space.alignmentProven) {
            return blocked("refusing a claim that coordinate alignment is proven")
        }
        val liveA11y = serviceProvider()?.canDispatchGestures() == true
        val again = DispatchRecheck.evaluate(
            permit.copy(
                a11yConnected = permit.a11yConnected && liveA11y,
                inputEnabled = permit.inputEnabled && liveInput,
                captureOn = permit.captureOn && liveCapture,
                screenWidth = screen.widthPx,
                screenHeight = screen.heightPx,
                screenSource = screen.source,
            ),
            nowElapsedMs = nowElapsedMs(),
        )
        if (!again.allow) return blocked(again.reason)
        return dispatchToChannel(permit.gesture)
    }

    /**
     * Unguarded entry. Refused so auto-play cannot skip [dispatchChecked].
     * TESZT ÉRINTÉS uses [dispatchManualTest].
     */
    override fun dispatch(gesture: GestureSpec): InputDispatchResult =
        InputDispatchResult.Failed(UNGUARDED_REFUSAL)

    /**
     * Bubble «TESZT ÉRINTÉS» only. No vision gate. A completed callback is not
     * evidence that the automatic vision-gated path works.
     */
    fun dispatchManualTest(gesture: GestureSpec): InputDispatchResult = dispatchToChannel(gesture)

    private fun dispatchToChannel(gesture: GestureSpec): InputDispatchResult {
        val svc = serviceProvider()
            ?: return InputDispatchResult.Failed("AccessibilityService not connected")
        if (!svc.canDispatchGestures()) {
            return InputDispatchResult.Failed(
                "AccessibilityService cannot dispatch gestures (${svc.diagnose()})",
            )
        }
        return svc.dispatchGesture(gesture, awaitCompletion = true)
    }

    private fun readScreen(): ScreenMeasurement? = try {
        liveProbe.screen()
    } catch (_: Throwable) {
        ScreenMeasurement.unavailable("screen-probe-threw")
    }

    private fun <T> readOrNull(block: () -> T?): T? = try {
        block()
    } catch (_: Throwable) {
        null
    }

    private fun blocked(reason: String): InputDispatchResult =
        InputDispatchResult.Failed("TOCTOU recheck blocked dispatchGesture: $reason")

    companion object {
        const val UNGUARDED_REFUSAL =
            "unguarded dispatch() refused on the production executor; " +
                "auto-play must use dispatchChecked; TESZT ÉRINTÉS must use dispatchManualTest " +
                "(manual path is not a vision-gated automatic move)"
    }
}
