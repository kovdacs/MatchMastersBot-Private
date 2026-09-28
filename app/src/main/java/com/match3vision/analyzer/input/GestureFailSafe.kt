package com.match3vision.analyzer.input

import com.match3vision.analyzer.capture.FrameSequenceGate
import com.match3vision.analyzer.evaluation.MoveEvaluation
import com.match3vision.analyzer.vision.ValidationResult
import com.match3vision.analyzer.vision.VisionResult
import com.match3vision.analyzer.vision.VisionThresholds
import com.match3vision.analyzer.vision.VisionValidator

/**
 * Consolidated pre-gesture fail-safes. **None** of these may reach dispatchGesture.
 *
 * Thresholds are never loosened:
 * - MIN_GRID_CONFIDENCE = 0.98
 * - MIN_BOARD_CONFIDENCE = 0.95
 * - MAX_UNKNOWN_COUNT = 1
 *
 * Cascade-refill [MoveEvaluation.uncertain] with confidence ≥ [InputThresholds.MIN_MOVE_CONFIDENCE]
 * remains allowed (see 9301892). Blind / low-confidence uncertain is blocked.
 */
object GestureFailSafe {

    data class Context(
        val vision: VisionResult,
        val move: MoveEvaluation?,
        val inputEnabled: Boolean,
        val executorReady: Boolean,
        val a11yConnected: Boolean = true,
        val captureOk: Boolean = true,
        val hasFrame: Boolean = true,
        val frameAgeMs: Long = 0L,
        val maxFrameAgeMs: Long = MAX_FRAME_AGE_MS,
        val frameSequenceDecision: FrameSequenceGate.Decision? = null,
        val minMoveConfidence: Float = InputThresholds.MIN_MOVE_CONFIDENCE,
    )

    data class Result(val allow: Boolean, val reason: String)

    private val visionValidator = VisionValidator()

    fun evaluate(ctx: Context): Result {
        if (!ctx.captureOk) {
            return Result(false, FrameSequenceGate.HOLD_CAPTURE_ERROR)
        }
        if (!ctx.hasFrame) {
            return Result(false, FrameSequenceGate.HOLD_NO_FRESH_FRAME)
        }
        val seq = ctx.frameSequenceDecision
        if (seq != null && !seq.allow) {
            return Result(false, seq.reason)
        }
        if (ctx.frameAgeMs > ctx.maxFrameAgeMs) {
            return Result(false, FrameSequenceGate.HOLD_STALE_FRAME +
                " age=${ctx.frameAgeMs}ms > ${ctx.maxFrameAgeMs}ms")
        }
        if (!ctx.inputEnabled) {
            return Result(false, AutomaticInputEngine.HOLD_INPUT_DISABLED)
        }
        if (!ctx.a11yConnected || !ctx.executorReady) {
            return Result(false, AutomaticInputEngine.HOLD_INPUT_CHANNEL_NOT_READY)
        }
        if (!ctx.vision.validation.isPass) {
            val reason = (ctx.vision.validation as? ValidationResult.Hold)?.reason
                ?: AutomaticInputEngine.HOLD_VISION_BLOCKED
            return Result(false, reason)
        }
        val thresholdGate = visionValidator.validate(
            boardConfidence = ctx.vision.boardConfidence,
            gridConfidence = ctx.vision.gridConfidence,
            unknownCount = ctx.vision.unknownCount,
        )
        if (thresholdGate is ValidationResult.Hold) {
            return Result(false, thresholdGate.reason)
        }
        if (ctx.vision.gridConfidence < VisionThresholds.MIN_GRID_CONFIDENCE) {
            return Result(false, "HOLD: grid confidence ${ctx.vision.gridConfidence} < ${VisionThresholds.MIN_GRID_CONFIDENCE}")
        }
        if (ctx.vision.boardConfidence < VisionThresholds.MIN_BOARD_CONFIDENCE) {
            return Result(false, "HOLD: board confidence ${ctx.vision.boardConfidence} < ${VisionThresholds.MIN_BOARD_CONFIDENCE}")
        }
        if (ctx.vision.unknownCount > VisionThresholds.MAX_UNKNOWN_COUNT) {
            return Result(false, AutomaticInputEngine.HOLD_TOO_MANY_UNKNOWN)
        }
        val move = ctx.move
        if (move == null) {
            return Result(false, AutomaticInputEngine.HOLD_NO_LEGAL_MOVE)
        }
        if (!move.expectedValue.isFinite() || move.expectedValue == Float.NEGATIVE_INFINITY) {
            return Result(false, AutomaticInputEngine.HOLD_NO_LEGAL_MOVE)
        }
        // Blind / low-confidence uncertain — never gesture. Cascade-uncertain with
        // adequate confidence (≥ MIN_MOVE_CONF) is allowed by design (9301892).
        if (move.uncertain && move.confidence < ctx.minMoveConfidence) {
            return Result(false, AutomaticInputEngine.HOLD_MOVE_UNCERTAIN)
        }
        if (move.confidence < ctx.minMoveConfidence) {
            return Result(
                false,
                "HOLD — move confidence %.3f < %.3f".format(move.confidence, ctx.minMoveConfidence),
            )
        }
        if (!ctx.vision.grid.isMonotonic()) {
            return Result(false, AutomaticInputEngine.HOLD_GRID_INVALID)
        }
        return Result(true, "OK")
    }

    const val MAX_FRAME_AGE_MS = 3_000L
}
