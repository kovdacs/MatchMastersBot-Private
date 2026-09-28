package com.match3vision.analyzer.input

/**
 * User-facing auto-play phase labels (Hungarian).
 * Never imply useful play while only HOLD-blocked.
 */
enum class LoopPhase(val labelHu: String) {
    IDLE("TÉTLEN"),
    STARTING("INDÍTÁS"),
    CAPTURE("RÖGZÍTÉS"),
    VISION_HOLD("TARTÁS (látás)"),
    VISION_PASS("LÁTÁS OK"),
    SELECT_MOVE("LÉPÉS"),
    INPUT_NOT_READY("BEVITEL NEM KÉSZ"),
    GESTURE("GESZTUS"),
    VERIFY("ELLENŐRZÉS"),
    PAUSED("SZÜNET"),
    STOPPED("STOP"),
    ERROR("HIBA"),
    HOLD_BLOCKED("TARTÁS"),
}

object LoopPhaseLabels {
    fun fromCycle(
        mode: AutoPlayController.Mode,
        visionGate: String,
        outcome: BotLoopOutcome?,
        inputReady: Boolean,
        dispatched: Boolean,
        verifying: Boolean = false,
    ): LoopPhase = when (mode) {
        AutoPlayController.Mode.IDLE -> LoopPhase.IDLE
        AutoPlayController.Mode.PAUSED -> LoopPhase.PAUSED
        AutoPlayController.Mode.STOPPED -> LoopPhase.STOPPED
        AutoPlayController.Mode.RUNNING -> when {
            verifying -> LoopPhase.VERIFY
            dispatched -> LoopPhase.GESTURE
            outcome == BotLoopOutcome.STOP -> LoopPhase.ERROR
            visionGate.startsWith("HOLD", ignoreCase = true) ||
                visionGate.contains("HOLD") -> LoopPhase.VISION_HOLD
            !inputReady && visionGate == "PASS" -> LoopPhase.INPUT_NOT_READY
            outcome == BotLoopOutcome.HOLD -> LoopPhase.HOLD_BLOCKED
            visionGate == "PASS" -> LoopPhase.VISION_PASS
            else -> LoopPhase.CAPTURE
        }
    }
}
