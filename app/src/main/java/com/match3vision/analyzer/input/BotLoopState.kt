package com.match3vision.analyzer.input

/**
 * AUTOMATIC INPUT ENGINE V1 feedback-loop state machine.
 *
 * ```
 * IDLE → CAPTURE → VALIDATE → ANALYZE → SELECT_MOVE → EXECUTE_INPUT
 *      → WAIT_FOR_BOARD → VERIFY_RESULT → CAPTURE …
 * errors / uncertainty → HOLD or STOP (fail-safe)
 * ```
 */
enum class BotLoopState {
    IDLE,
    CAPTURE,
    VALIDATE,
    ANALYZE,
    SELECT_MOVE,
    EXECUTE_INPUT,
    WAIT_FOR_BOARD,
    VERIFY_RESULT,
    HOLD,
    STOP,
}

enum class BotLoopOutcome {
    /** Continue the loop (e.g. next CAPTURE). */
    CONTINUE,
    /** Soft block — Decision AI / input blocked; may recover. */
    HOLD,
    /** Hard fail-safe stop — no blind retry. */
    STOP,
}

data class BotLoopTransition(
    val from: BotLoopState,
    val to: BotLoopState,
    val outcome: BotLoopOutcome,
    val reason: String,
)
