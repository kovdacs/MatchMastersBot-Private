package com.match3vision.analyzer.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import timber.log.Timber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
class MatchMastersAccessibilityService : AccessibilityService(), AccessibilityGestureChannel {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Re-assert gesture capability + retrieve windows (some OEMs drop XML caps).
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.DEFAULT or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        // Keep event types minimal; gestures do not require touch-exploration mode
        // (that would change system UX). canPerformGestures comes from XML meta-data.
        setServiceInfo(info)
        Timber.i("MatchMastersAccessibilityService connected — AccessibilityService ENABLED")
        Timber.i(
            "TOUCH_A11Y: AccessibilityService ENABLED=true (onServiceConnected) " +
                "capabilities=0x%X canPerformGestures=%s",
            info.capabilities,
            canDispatchGestures(),
        )
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

    /**
     * True when this service instance is live and may call framework [dispatchGesture].
     *
     * Logs a warning if [AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES] is missing
     * from serviceInfo (some OEMs omit the bit even when XML has canPerformGestures=true).
     * Still returns true so we attempt dispatch — the framework returns false if denied.
     */
    override fun canDispatchGestures(): Boolean {
        val info = serviceInfo
        if (info != null) {
            val hasBit =
                (info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0
            if (!hasBit) {
                Timber.w(
                    "TOUCH_A11Y: serviceInfo missing CAPABILITY_CAN_PERFORM_GESTURES " +
                        "(caps=0x%X) — XML has canPerformGestures; allowing dispatch attempt",
                    info.capabilities,
                )
            }
        } else {
            Timber.w("TOUCH_A11Y: serviceInfo=null — allowing dispatch attempt on live instance")
        }
        return true
    }

    /** Human-readable diagnose line for bubble / logcat. */
    override fun diagnose(): String {
        val info = serviceInfo
        val caps = info?.capabilities ?: -1
        val hasBit =
            info != null &&
                (info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0
        return "connected=true capabilityBit=$hasBit capabilities=0x${Integer.toHexString(caps)}"
    }

    /**
     * Dispatch a swipe/tap using [GestureDescription].
     *
     * [GestureSpec] x/y are display pixels in the same full-frame space
     * [com.match3vision.analyzer.input.TouchCoordinateMapper] produced.
     * The [android.graphics.Path] is not shifted by status bar, navigation bar,
     * cutout, or rotation. This method does not read [android.view.WindowInsets].
     * Whether those pixels match the physical panel is not proven here.
     *
     * When called **off the main thread**, waits for [GestureResultCallback] (completed /
     * cancelled / timeout) so callers learn the real outcome — not just "scheduled".
     * When called **on the main thread**, returns after schedule only (avoids deadlock
     * with the main-looper callback).
     *
     * Uses `super.dispatchGesture` explicitly so the GestureSpec overload never
     * shadows the framework call (a11y wiring fix).
     */
    override fun dispatchGesture(
        gesture: GestureSpec,
        awaitCompletion: Boolean,
        timeoutMs: Long,
    ): InputDispatchResult {
        if (!canDispatchGestures()) {
            Timber.e("TOUCH_A11Y: gesture dispatch FAIL — canPerformGestures=false (%s)", diagnose())
            return InputDispatchResult.Failed("canPerformGestures=false (${diagnose()})")
        }
        // Reject clearly off-screen / non-positive coords early.
        if (gesture.startX < 0f || gesture.startY < 0f ||
            gesture.endX < 0f || gesture.endY < 0f
        ) {
            return InputDispatchResult.Failed(
                "coords off-screen/negative start=(${gesture.startX},${gesture.startY}) " +
                    "end=(${gesture.endX},${gesture.endY})",
            )
        }
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
            } else {
                // Single-point tap: tiny path still needs a stroke duration.
                lineTo(gesture.startX + 1f, gesture.startY)
            }
        }
        val duration = gesture.durationMs.coerceAtLeast(MIN_STROKE_DURATION_MS)
        val stroke = GestureDescription.StrokeDescription(
            path,
            /* startTime= */ 0L,
            /* duration= */ duration,
        )
        val description = GestureDescription.Builder().addStroke(stroke).build()
        Timber.i("TOUCH_A11Y: gesture dispatch happening (AccessibilityService.dispatchGesture)")

        val onMain = Looper.myLooper() == Looper.getMainLooper()
        val shouldAwait = awaitCompletion && !onMain
        if (awaitCompletion && onMain) {
            Timber.w(
                "TOUCH_A11Y: dispatch on main thread — cannot await callback (would deadlock); " +
                    "returning scheduled-only result",
            )
        }

        val outcome = AtomicReference<CallbackOutcome?>(null)
        val latch = CountDownLatch(1)
        val callbackHandler = Handler(Looper.getMainLooper())

        val ok = try {
            super.dispatchGesture(
                description,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Timber.i("TOUCH_A11Y: gesture dispatch SUCCESS (onCompleted) $gesture")
                        outcome.set(CallbackOutcome.COMPLETED)
                        latch.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Timber.w("TOUCH_A11Y: gesture dispatch FAIL (onCancelled) $gesture")
                        outcome.set(CallbackOutcome.CANCELLED)
                        latch.countDown()
                    }
                },
                callbackHandler,
            )
        } catch (t: Throwable) {
            Timber.e(t, "TOUCH_A11Y: gesture dispatch FAIL (exception)")
            return InputDispatchResult.Failed(
                "AccessibilityService.dispatchGesture threw: ${t.message}",
            )
        }

        if (!ok) {
            Timber.e("TOUCH_A11Y: gesture dispatch FAIL — dispatchGesture returned false")
            return GestureCallbackPolicy.decide(
                scheduled = false,
                awaitCallback = shouldAwait,
                callbackArrived = false,
                completed = false,
                cancelled = false,
            ).toResult(gesture)
        }

        Timber.i("TOUCH_A11Y: dispatchGesture returned true (scheduled) await=%s", shouldAwait)
        val finished = if (!shouldAwait) {
            false
        } else {
            try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }
        val decision = GestureCallbackPolicy.decide(
            scheduled = true,
            awaitCallback = shouldAwait,
            callbackArrived = finished,
            completed = outcome.get() == CallbackOutcome.COMPLETED,
            cancelled = outcome.get() == CallbackOutcome.CANCELLED,
        )
        when (decision.kind) {
            GestureCallbackPolicy.Kind.TIMED_OUT ->
                Timber.e("TOUCH_A11Y: gesture dispatch FAIL — %s", decision.reason)
            GestureCallbackPolicy.Kind.CANCELLED ->
                Timber.w("TOUCH_A11Y: gesture dispatch FAIL — %s", decision.reason)
            GestureCallbackPolicy.Kind.COMPLETED ->
                Timber.i("TOUCH_A11Y: gesture callback onCompleted (not VERIFY SUCCESS)")
            else -> Unit
        }
        return decision.toResult(gesture)
    }

    private enum class CallbackOutcome { COMPLETED, CANCELLED }

    companion object {
        /** Framework stroke duration floor — very short strokes are often cancelled. */
        const val MIN_STROKE_DURATION_MS = 50L

        const val GESTURE_CALLBACK_TIMEOUT_MS = 3_000L

        @Volatile
        private var instance: MatchMastersAccessibilityService? = null

        fun instanceOrNull(): MatchMastersAccessibilityService? = instance

        fun isConnected(): Boolean = instance != null

        fun diagnoseConnected(): String {
            val svc = instance ?: return "connected=false canPerformGestures=false"
            return svc.diagnose()
        }
    }
}
