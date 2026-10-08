package com.match3vision.analyzer.input

/**
 * Production-gated one-move protocol.
 *
 * ```
 * ARM → ANALYZING → ONE MOVE → VERIFYING → PAUSED → STOPPED
 * ```
 *
 * One [arm] permits exactly one production dispatch. After that dispatch the
 * machine leaves the states that allow a dispatch, so a later cycle cannot
 * issue another move until the user arms again. HOLD and manual test touches
 * do not consume the arm. This machine does not evaluate vision or dispatch
 * a gesture.
 */
class SingleMoveMachine {
    enum class Phase {
        IDLE,
        ARMED,
        ANALYZING,
        ONE_MOVE,
        VERIFYING,
        PAUSED,
        STOPPED,
    }

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    @Volatile
    var productionDispatches: Int = 0
        private set

    fun label(): String = when (phase) {
        Phase.ONE_MOVE -> "ONE MOVE"
        else -> phase.name
    }

    /** Explicit user arm. Refused while a protocol is already in progress. */
    fun arm(): Boolean {
        if (phase != Phase.IDLE && phase != Phase.STOPPED && phase != Phase.PAUSED) {
            return false
        }
        phase = Phase.ARMED
        productionDispatches = 0
        return true
    }

    fun onObserve() {
        if (phase == Phase.ARMED) phase = Phase.ANALYZING
    }

    fun allowsProductionDispatch(): Boolean =
        productionDispatches == 0 && (phase == Phase.ARMED || phase == Phase.ANALYZING)

    /**
     * Records the one production dispatch and moves to ONE MOVE.
     * Returns false when a dispatch is not permitted. Does not throw.
     */
    fun tryProductionDispatch(): Boolean {
        if (!allowsProductionDispatch()) return false
        productionDispatches = 1
        phase = Phase.ONE_MOVE
        return true
    }

    fun beginVerify() {
        if (phase == Phase.ONE_MOVE) phase = Phase.VERIFYING
    }

    fun onVerified() {
        if (phase == Phase.VERIFYING) phase = Phase.PAUSED
    }

    /** Export finished, or verify was abandoned. No further production move. */
    fun finishStopped() {
        if (phase == Phase.IDLE) return
        phase = Phase.STOPPED
    }

    /** No dispatch was consumed. A later explicit arm may start cleanly. */
    fun resetIdle() {
        phase = Phase.IDLE
        productionDispatches = 0
    }
}
