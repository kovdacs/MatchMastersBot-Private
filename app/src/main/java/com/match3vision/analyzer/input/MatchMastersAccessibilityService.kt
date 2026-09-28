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
 * - App-level [InputEnableSwitch] must also be explicitly enabled (default off).
 * - Until both are on, [AutomaticInputEngine] never calls this path.
 *
 * Alternative documented path: [ShellInputGestureExecutor] (`input swipe` via shell /
 * Instrumentation). Prefer this AccessibilityService when available.
 */
class MatchMastersAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Timber.i("MatchMastersAccessibilityService connected (gestures gated by InputEnableSwitch)")
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
        Timber.i("MatchMastersAccessibilityService destroyed")
    }

    fun canDispatchGestures(): Boolean = true

    /**
     * Dispatch a swipe/tap using [GestureDescription]. Returns immediately after
     * scheduling; completion is asynchronous on the a11y thread.
     */
    fun dispatchGesture(gesture: GestureSpec): InputDispatchResult {
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
        val ok = dispatchGesture(
            description,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Timber.d("gesture completed: $gesture")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Timber.w("gesture cancelled: $gesture")
                }
            },
            null,
        )
        return if (ok) {
            InputDispatchResult.Dispatched(gesture)
        } else {
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
