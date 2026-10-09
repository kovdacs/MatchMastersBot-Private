package com.match3vision.analyzer.hud

/**
 * The ACTIVATE pill flashes, so one frame can miss the word.
 * A positive word shape on any of the last [WINDOW] frames keeps the word.
 * Raw brightness is not a positive.
 */
class HudPulse {
    private val hits = ArrayDeque<Boolean>()

    fun apply(raw: HudObservation): HudObservation {
        if (raw.turnState == HudObservation.TURN_OPPONENT) {
            hits.clear()
            return raw
        }
        hits.addLast(raw.activateWord)
        while (hits.size > WINDOW) hits.removeFirst()
        if (hits.none { it } || raw.activateWord) return raw
        return raw.copy(
            activateWord = true,
            activate = "yes",
            boosterFill = if (raw.boosterFill == "unknown" || raw.boosterFill == "not detectable") {
                "ACTIVATE"
            } else {
                raw.boosterFill
            },
            boosterTarget = "none",
            boosterPhase = BoosterMonitor.READY,
        )
    }

    fun clear() {
        hits.clear()
    }

    companion object {
        const val WINDOW = 5
    }
}
