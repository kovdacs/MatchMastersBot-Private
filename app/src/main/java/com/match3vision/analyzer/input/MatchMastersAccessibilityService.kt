package com.match3vision.analyzer.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import timber.log.Timber

/**
 * Real Android touch/swipe path via [AccessibilityService.dispatchGesture].
 *
 * **Clear enable gate:**
 * - Declared in the manifest but **not** auto-activated.
 * - User must enable the service in system Accessibility settings.
 * - App-level [InputEnableSwitch] must also be explicitly enabled (default off)
 *   for auto-play / smoke paths. Isolated [AutomaticTouchTest] dispatches directly
 *   after the bubble «TESZT ÉRINTÉS» press (explicit user action).
 *
 * Alternative documented path: [ShellInputGestureExecutor] (`input swipe` via shell /
 * Instrumentation). Prefer this AccessibilityService when available.
 */
class MatchMastersAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Timber.i("MatchMastersAccessibilityService connected — AccessibilityService ENABLED")
        Timber.i("TOUCH_A11Y: AccessibilityService ENABLED=true (onServiceConnected)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Analyzer/input engine does not react to a11y events for auto-play.
    }

    override fun onInterrupt() {
        Timber.w("MatchMastersAccessibilityService interrupted")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
        Timber.i("MatchMastersAccessibilityService destroyed — AccessibilityService ENABLED=false")
        Timber.i("TOUCH_A11Y: AccessibilityService ENABLED=false (onDestroy)")
    }

    /** Service instance is live and may call [AccessibilityService.dispatchGesture]. */
    fun canDispatchGestures(): Boolean = true

    /**
     * Dispatch a swipe/tap using [GestureDescription]. Returns immediately after
     * scheduling; completion is asynchronous on the a11y thread.
     *
     * Uses `super.dispatchGesture` explicitly so the GestureSpec overload never
     * shadows the framework call (a11y wiring fix).
     */
    fun dispatchGesture(gesture: GestureSpec): InputDispatchResult {
        Timber.i(
            "TOUCH_A11Y: gesture created start=(%.1f,%.1f) end=(%.1f,%.1f) durationMs=%d",
            gesture.startX,
            gesture.startY,
            gesture.endX,
            gesture.endY,
            gesture.durationMs,
        )
        val path = Path().apply {
            moveTo(gesture.startX, gesture.startY)
            if (gesture.isSwipe) {
                lineTo(gesture.endX, gesture.endY)
            }
        }
        val stroke = GestureDescription.StrokeDescription(
            path,
            /* startTime= */ 0L,
            /* duration= */ gesture.durationMs.coerceAtLeast(1L),
        )
        val description = GestureDescription.Builder().addStroke(stroke).build()
        Timber.i("TOUCH_A11Y: gesture dispatch happening (AccessibilityService.dispatchGesture)")
        val ok = try {
            super.dispatchGesture(
                description,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Timber.i("TOUCH_A11Y: gesture dispatch SUCCESS (onCompleted) $gesture")
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Timber.w("TOUCH_A11Y: gesture dispatch FAIL (onCancelled) $gesture")
                    }
                },
                null,
            )
        } catch (t: Throwable) {
            Timber.e(t, "TOUCH_A11Y: gesture dispatch FAIL (exception)")
            return InputDispatchResult.Failed(
                "AccessibilityService.dispatchGesture threw: ${t.message}",
            )
        }
        return if (ok) {
            Timber.i("TOUCH_A11Y: dispatchGesture returned true (scheduled)")
            InputDispatchResult.Dispatched(gesture)
        } else {
            Timber.e("TOUCH_A11Y: gesture dispatch FAIL — dispatchGesture returned false")
            InputDispatchResult.Failed("AccessibilityService.dispatchGesture returned false")
        }
    }

    companion object {
        @Volatile
        private var instance: MatchMastersAccessibilityService? = null

        fun instanceOrNull(): MatchMastersAccessibilityService? = instance

        fun isConnected(): Boolean = instance != null
    }
}
