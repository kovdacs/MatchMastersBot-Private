package com.match3vision.analyzer.input

import com.match3vision.analyzer.vision.VisionResult
import com.match3vision.analyzer.vision.VisionThresholds

/**
 * Last check before [AccessibilityGestureChannel.dispatchGesture].
 *
 * Plan-time gates in the bubble can go stale (TOCTOU): a frame can age out,
 * accessibility can drop, or the vision result can be HOLD by the time the
 * gesture is handed to the service. [AccessibilityGestureExecutor.dispatchChecked]
 * runs this again and does not call dispatchGesture when it fails.
 */
data class DispatchPermit(
    val a11yConnected: Boolean,
    val captureOn: Boolean,
    val hasFrame: Boolean,
    val frameAgeMs: Long,
    val visionPass: Boolean,
    val inputEnabled: Boolean,
    val screenWidth: Int,
    val screenHeight: Int,
    val frameWidth: Int,
    val frameHeight: Int,
    val gesture: GestureSpec,
    val simulated: Boolean,
    val sequenceAllowed: Boolean,
    /** Monotonic capture time. 0 if the frame did not record one. */
    val capturedElapsedMs: Long = 0L,
) {
    companion object {
        fun from(
            context: RuntimeCycleContext,
            vision: VisionResult,
            gesture: GestureSpec,
            inputEnabled: Boolean,
        ): DispatchPermit {
            val thresholdsPass = vision.validation.isPass &&
                vision.gridConfidence >= VisionThresholds.MIN_GRID_CONFIDENCE &&
                vision.boardConfidence >= VisionThresholds.MIN_BOARD_CONFIDENCE &&
                vision.unknownCount <= VisionThresholds.MAX_UNKNOWN_COUNT
            val seqOk = context.frameSequenceDecision?.allow != false
            return DispatchPermit(
                a11yConnected = context.a11yConnected,
                captureOn = context.captureOn,
                hasFrame = context.hasFrame,
                frameAgeMs = context.frameAgeMs,
                visionPass = thresholdsPass,
                inputEnabled = inputEnabled,
                screenWidth = context.screenWidth,
                screenHeight = context.screenHeight,
                frameWidth = context.frameWidth,
                frameHeight = context.frameHeight,
                gesture = gesture,
                simulated = context.simulated,
                sequenceAllowed = seqOk,
                capturedElapsedMs = context.capturedElapsedMs,
            )
        }
    }
}

object DispatchRecheck {
    data class Result(val allow: Boolean, val reason: String)

    /**
     * [nowElapsedMs] is the monotonic clock at the dispatch call.
     * When both it and [DispatchPermit.capturedElapsedMs] are positive, age is
     * recomputed. Otherwise the age already on the permit is used.
     */
    fun evaluate(permit: DispatchPermit, nowElapsedMs: Long = 0L): Result {
        val ageMs = if (permit.capturedElapsedMs > 0L && nowElapsedMs > 0L) {
            FrameClock.ageMs(permit.capturedElapsedMs, nowElapsedMs)
        } else {
            permit.frameAgeMs
        }
        if (permit.simulated) {
            return Result(false, "simulated context is not a production dispatch")
        }
        if (!permit.captureOn) return Result(false, "CAPTURE: OFF")
        if (!permit.hasFrame) return Result(false, "FRAME: no frame")
        if (!permit.sequenceAllowed) {
            return Result(false, "frame sequence rejected")
        }
        if (ageMs > GestureFailSafe.MAX_FRAME_AGE_MS) {
            return Result(false, "stale frame age=${ageMs}ms")
        }
        if (!permit.visionPass) return Result(false, "VISION HOLD")
        if (!permit.inputEnabled) return Result(false, "input disabled")
        if (!permit.a11yConnected) return Result(false, "ACCESSIBILITY: DISCONNECTED")
        val space = FrameScreenCoordinatePolicy.assess(
            frameWidth = permit.frameWidth,
            frameHeight = permit.frameHeight,
            screenWidth = permit.screenWidth,
            screenHeight = permit.screenHeight,
        )
        if (space.mapping != FrameScreenCoordinatePolicy.Mapping.IDENTITY_FRAME_PIXELS) {
            return Result(false, space.reason)
        }
        val bounds = CoordinateBounds.check(permit.gesture, permit.screenWidth, permit.screenHeight)
        if (!bounds.allow) return Result(false, bounds.reason)
        return Result(true, "OK")
    }
}
