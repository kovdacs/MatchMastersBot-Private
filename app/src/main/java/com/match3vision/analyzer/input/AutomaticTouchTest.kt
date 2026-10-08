package com.match3vision.analyzer.input

/**
 * Isolated AUTOMATIC TOUCH TEST — no Vision, no PASS/HOLD/grid/unk, no play loop.
 *
 * On bubble «TESZT ÉRINTÉS»: check AccessibilityService → build ONE fixed-coordinate
 * visible swipe → dispatch via [InputGestureExecutor] → log ENABLED / created /
 * dispatch / success|fail (awaits GestureResultCallback off main thread).
 *
 * Coordinates are predetermined from screen size (not from Vision/GridGeometry):
 *   start = (width/2, height*45/100)
 *   end   = (startX + SWIPE_DELTA_PX, startY)
 * Example 1080×2340: (540, 1053) → (740, 1053)
 */
class AutomaticTouchTest(
    private val executor: InputGestureExecutor = ProductionInstall.accessibilityExecutor(),
    private val a11yConnected: () -> Boolean = { MatchMastersAccessibilityService.isConnected() },
    private val a11yDiagnose: () -> String = { MatchMastersAccessibilityService.diagnoseConnected() },
    private val logger: SmokeTestLogger = SmokeTestLogger(),
) {

    data class Result(
        val success: Boolean,
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val durationMs: Long,
        val reason: String,
        val a11yEnabled: Boolean,
        val gestureCreated: Boolean,
        val dispatchAttempted: Boolean,
        /** Compact Hungarian status for bubble / Toast. */
        val huStatus: String,
        val a11yDiagnose: String,
        val screenWidthPx: Int,
        val screenHeightPx: Int,
    )

    fun logger(): SmokeTestLogger = logger

    /**
     * Resolve fixed predetermined screen coordinates from display size.
     * Not Vision-derived; same formula on every press for a given resolution.
     */
    fun resolveCoords(screenWidthPx: Int, screenHeightPx: Int): GestureSpec {
        require(screenWidthPx > 0 && screenHeightPx > 0) {
            "screen size must be positive"
        }
        val startX = screenWidthPx / 2f
        val startY = (screenHeightPx * FIXED_Y_NUMERATOR) / FIXED_Y_DENOMINATOR.toFloat()
        val delta = SWIPE_DELTA_PX.coerceAtMost(screenWidthPx / 5f).coerceAtLeast(80f)
        val endX = (startX + delta).coerceAtMost(screenWidthPx - 1f)
        val endY = startY
        return GestureSpec(
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            durationMs = GESTURE_DURATION_MS,
        )
    }

    /**
     * Fire ONE visible test gesture. Never analyzes frames or consults Vision gates.
     * Prefer calling from a **background** thread so [AccessibilityGestureExecutor]
     * can await onCompleted / onCancelled without deadlocking the main looper.
     */
    fun runOnce(screenWidthPx: Int, screenHeightPx: Int): Result {
        logger.log("——— AUTOMATIC TOUCH TEST START ———")
        val diagnose = a11yDiagnose()
        val connected = a11yConnected()
        val ready = executor.isReady()
        val a11yEnabled = connected && ready
        logger.log(
            "AccessibilityService ENABLED=${if (a11yEnabled) "true" else "false"} " +
                "connected=$connected executorReady=$ready diagnose=$diagnose",
        )
        logger.log("screen=${screenWidthPx}x${screenHeightPx}")
        if (!connected) {
            val reason = "FAIL — AccessibilityService not ENABLED/connected"
            val hu = "a11y=NEM — kapcsold be: Beállítások→Kisegítő lehetőségek"
            logger.log(reason)
            logger.log(hu)
            return Result(
                success = false,
                startX = 0f,
                startY = 0f,
                endX = 0f,
                endY = 0f,
                durationMs = GESTURE_DURATION_MS,
                reason = reason,
                a11yEnabled = false,
                gestureCreated = false,
                dispatchAttempted = false,
                huStatus = hu,
                a11yDiagnose = diagnose,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
            )
        }
        if (!ready) {
            val reason = "FAIL — AccessibilityService connected but canPerformGestures=false ($diagnose)"
            val hu = "a11y=IGEN de gesztus NEM — canPerformGestures=false"
            logger.log(reason)
            logger.log(hu)
            return Result(
                success = false,
                startX = 0f,
                startY = 0f,
                endX = 0f,
                endY = 0f,
                durationMs = GESTURE_DURATION_MS,
                reason = reason,
                a11yEnabled = false,
                gestureCreated = false,
                dispatchAttempted = false,
                huStatus = hu,
                a11yDiagnose = diagnose,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
            )
        }

        val gesture = resolveCoords(screenWidthPx, screenHeightPx)
        val coord =
            "(${gesture.startX.toInt()},${gesture.startY.toInt()})→" +
                "(${gesture.endX.toInt()},${gesture.endY.toInt()})"
        logger.log(
            "gesture created: start=(${gesture.startX.toInt()},${gesture.startY.toInt()}) " +
                "end=(${gesture.endX.toInt()},${gesture.endY.toInt()}) " +
                "durationMs=${gesture.durationMs} (FIXED predetermined; no Vision)",
        )

        logger.log(
            "MANUAL PATH — TESZT ÉRINTÉS — not evidence that the vision-gated automatic path works",
        )
        logger.log("gesture dispatch happening…")
        val dispatch = dispatchManual(gesture)
        return when (dispatch) {
            is InputDispatchResult.Dispatched -> {
                val msg =
                    "gesture dispatch SUCCESS start=(${gesture.startX.toInt()},${gesture.startY.toInt()}) " +
                        "end=(${gesture.endX.toInt()},${gesture.endY.toInt()})"
                val hu = "a11y=IGEN koordináták=$coord OK (onCompleted)"
                logger.log(msg)
                logger.log(hu)
                logger.log("——— AUTOMATIC TOUCH TEST PASS ———")
                Result(
                    success = true,
                    startX = gesture.startX,
                    startY = gesture.startY,
                    endX = gesture.endX,
                    endY = gesture.endY,
                    durationMs = gesture.durationMs,
                    reason = msg,
                    a11yEnabled = true,
                    gestureCreated = true,
                    dispatchAttempted = true,
                    huStatus = hu,
                    a11yDiagnose = diagnose,
                    screenWidthPx = screenWidthPx,
                    screenHeightPx = screenHeightPx,
                )
            }
            is InputDispatchResult.Failed -> {
                val msg = "gesture dispatch FAIL: ${dispatch.reason}"
                val hu = "a11y=IGEN koordináták=$coord FAIL: ${dispatch.reason.take(64)}"
                logger.log(msg)
                logger.log(hu)
                logger.log("——— AUTOMATIC TOUCH TEST FAIL ———")
                Result(
                    success = false,
                    startX = gesture.startX,
                    startY = gesture.startY,
                    endX = gesture.endX,
                    endY = gesture.endY,
                    durationMs = gesture.durationMs,
                    reason = msg,
                    a11yEnabled = true,
                    gestureCreated = true,
                    dispatchAttempted = true,
                    huStatus = hu,
                    a11yDiagnose = diagnose,
                    screenWidthPx = screenWidthPx,
                    screenHeightPx = screenHeightPx,
                )
            }
        }
    }

    /**
     * Production executor refuses [InputGestureExecutor.dispatch]. The manual
     * test uses [AccessibilityGestureExecutor.dispatchManualTest] so it cannot
     * be mistaken for [AccessibilityGestureExecutor.dispatchChecked].
     */
    private fun dispatchManual(gesture: GestureSpec): InputDispatchResult {
        val channel = executor
        return if (channel is AccessibilityGestureExecutor) {
            channel.dispatchManualTest(gesture)
        } else {
            channel.dispatch(gesture)
        }
    }

    companion object {
        /** Y = height * 45 / 100 — center-ish of phone (above bottom chrome). */
        const val FIXED_Y_NUMERATOR = 45
        const val FIXED_Y_DENOMINATOR = 100

        /** Visible horizontal swipe so the user can see the touch. */
        const val SWIPE_DELTA_PX = 200f

        /** Longer than auto-play default so OEM gesture injectors reliably complete. */
        const val GESTURE_DURATION_MS = 400L

        /** Documented example for a 1080×2340 display. */
        const val EXAMPLE_1080x2340_X = 540
        const val EXAMPLE_1080x2340_Y = 1053
        const val EXAMPLE_1080x2340_END_X = 740
    }
}
