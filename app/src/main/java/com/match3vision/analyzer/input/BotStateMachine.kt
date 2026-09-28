package com.match3vision.analyzer.input

/**
 * Deterministic state machine for the capture → analyze → input → verify loop.
 *
 * ```
 * IDLE → CAPTURE → VALIDATE → ANALYZE → SELECT_MOVE → EXECUTE_INPUT
 *      → WAIT_FOR_BOARD → VERIFY_RESULT → CAPTURE …
 * errors / uncertainty → HOLD or STOP (fail-safe)
 * ```
 *
 * Unknown / failed verification / repeated failure → STOP (no blind retry).
 * Soft gate failures → HOLD (no input).
 */
class BotStateMachine(
    initial: BotLoopState = BotLoopState.IDLE,
    private val maxVerifyFailures: Int = InputThresholds.MAX_VERIFY_FAILURES,
) {
    @Volatile
    var state: BotLoopState = initial
        private set

    @Volatile
    var verifyFailureCount: Int = 0
        private set

    @Volatile
    var lastReason: String = "idle"
        private set

    val isTerminal: Boolean
        get() = state == BotLoopState.STOP

    val isHolding: Boolean
        get() = state == BotLoopState.HOLD

    val allowsInput: Boolean
        get() = state == BotLoopState.EXECUTE_INPUT

    fun reset() {
        state = BotLoopState.IDLE
        verifyFailureCount = 0
        lastReason = "reset"
    }

    fun startCapture(): BotLoopTransition {
        if (state == BotLoopState.STOP) {
            return BotLoopTransition(state, state, BotLoopOutcome.STOP, lastReason)
        }
        return advance(BotLoopState.CAPTURE, "capture frame")
    }

    fun onValidationPass(): BotLoopTransition {
        if (state != BotLoopState.CAPTURE && state != BotLoopState.VALIDATE) {
            return stop("STOP — illegal transition from $state (expected CAPTURE)")
        }
        if (state == BotLoopState.CAPTURE) {
            advance(BotLoopState.VALIDATE, "vision PASS")
        }
        return advance(BotLoopState.ANALYZE, "analyze board")
    }

    fun onValidationHold(reason: String): BotLoopTransition =
        hold(reason.ifBlank { "HOLD — vision validation failed" })

    fun onAnalysisReady(): BotLoopTransition =
        advanceFrom(BotLoopState.ANALYZE, BotLoopState.SELECT_MOVE, "select move")

    fun onNoLegalMove(reason: String = "HOLD — no legal move"): BotLoopTransition =
        hold(reason)

    fun onMoveSelected(): BotLoopTransition =
        advanceFrom(BotLoopState.SELECT_MOVE, BotLoopState.EXECUTE_INPUT, "execute input")

    fun onInputExecuted(): BotLoopTransition =
        advanceFrom(BotLoopState.EXECUTE_INPUT, BotLoopState.WAIT_FOR_BOARD, "wait for animation")

    fun onInputBlocked(reason: String): BotLoopTransition = hold(reason)

    fun onBoardReadyForVerify(): BotLoopTransition =
        advanceFrom(BotLoopState.WAIT_FOR_BOARD, BotLoopState.VERIFY_RESULT, "verify board change")

    fun onVerifySuccess(): BotLoopTransition {
        verifyFailureCount = 0
        return advanceFrom(
            BotLoopState.VERIFY_RESULT,
            BotLoopState.CAPTURE,
            "board changed — next cycle",
        )
    }

    fun onVerifyFailed(reason: String): BotLoopTransition {
        verifyFailureCount += 1
        return if (verifyFailureCount > maxVerifyFailures) {
            stop("STOP — repeated verify failure ($verifyFailureCount): $reason")
        } else {
            // First failure also STOP — no blind retry (requirement 8).
            stop("STOP — verify failed (no blind retry): $reason")
        }
    }

    fun onInvalidNewFrame(reason: String): BotLoopTransition =
        stop("STOP — invalid new frame: $reason")

    fun onUnknownOrUncertain(reason: String): BotLoopTransition =
        stop("STOP — fail-safe (unknown/uncertain): $reason")

    fun hold(reason: String): BotLoopTransition {
        val from = state
        state = BotLoopState.HOLD
        lastReason = reason
        return BotLoopTransition(from, state, BotLoopOutcome.HOLD, reason)
    }

    fun stop(reason: String): BotLoopTransition {
        val from = state
        state = BotLoopState.STOP
        lastReason = reason
        return BotLoopTransition(from, state, BotLoopOutcome.STOP, reason)
    }

    private fun advance(to: BotLoopState, reason: String): BotLoopTransition {
        val from = state
        state = to
        lastReason = reason
        return BotLoopTransition(from, to, BotLoopOutcome.CONTINUE, reason)
    }

    private fun advanceFrom(
        expected: BotLoopState,
        to: BotLoopState,
        reason: String,
    ): BotLoopTransition {
        if (state != expected) {
            return stop("STOP — illegal transition from $state (expected $expected) → $to")
        }
        return advance(to, reason)
    }
}
