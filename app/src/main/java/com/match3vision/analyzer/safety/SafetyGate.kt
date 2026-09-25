package com.match3vision.analyzer.safety

import com.match3vision.analyzer.board.BoardHistory
import com.match3vision.analyzer.board.GameState

/**
 * Fail-closed safety. Any uncertainty → HOLD. Never executes input.
 */
class SafetyGate(
    private val maxUnknown: Int = 1,
    private val minBoardConfidence: Float = 0.95f,
    private val minGridConfidence: Float = 0.98f,
    private val minFrameIntervalMs: Long = 200L,
    private val history: BoardHistory = BoardHistory(),
) {
    @Volatile private var emergencyStop: Boolean = false
    @Volatile private var lastFrameTs: Long = 0L

    data class SafetyResult(val allowDecision: Boolean, val reason: String)

    fun engageEmergencyStop() { emergencyStop = true }
    fun clearEmergencyStop() { emergencyStop = false }
    fun isEmergencyStopped(): Boolean = emergencyStop

    fun check(state: GameState, timestampMs: Long): SafetyResult {
        if (emergencyStop) return SafetyResult(false, "HOLD — emergency stop")
        if (!state.vision.gatePass) {
            return SafetyResult(false, state.vision.holdReason ?: "HOLD — vision gate")
        }
        if (state.vision.unknownCount > maxUnknown) {
            return SafetyResult(false, "HOLD — unknownCount=${state.vision.unknownCount}")
        }
        if (state.vision.boardConfidence < minBoardConfidence) {
            return SafetyResult(false, "HOLD — low board confidence")
        }
        if (state.vision.gridConfidence < minGridConfidence) {
            return SafetyResult(false, "HOLD — low grid confidence")
        }
        if (lastFrameTs > 0 && timestampMs - lastFrameTs < minFrameIntervalMs) {
            return SafetyResult(false, "HOLD — rate limited")
        }
        if (history.isDuplicateOfLast(state.board)) {
            return SafetyResult(false, "HOLD — duplicate/stale frame")
        }
        if (!state.board.isValidStructure()) {
            return SafetyResult(false, "HOLD — board structure mismatch")
        }
        history.push(state.board, timestampMs)
        lastFrameTs = timestampMs
        return SafetyResult(true, "OK")
    }

    fun recover() {
        emergencyStop = false
        lastFrameTs = 0L
        history.clear()
    }
}
