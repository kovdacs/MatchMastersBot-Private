package com.match3vision.analyzer.hud

/**
 * The ACTIVATE pill flashes, so one frame can miss the word.
 * A positive word shape on any of the last [WINDOW] frames keeps the word.
 * Raw brightness is not a positive.
 */
class HudPulse {
    private val hits = ArrayDeque<Boolean>()
    private val bars = ArrayDeque<Boolean>()

    fun apply(raw: HudObservation): HudObservation {
        if (raw.turnState == HudObservation.TURN_OPPONENT) {
            hits.clear()
            bars.clear()
            return raw.copy(boosterReady = false)
        }
        hits.addLast(raw.activateWord)
        bars.addLast(raw.barFull)
        while (hits.size > WINDOW) hits.removeFirst()
        while (bars.size > WINDOW) bars.removeFirst()
        val word = hits.any { it }
        val full = bars.count { it } >= FULL_HITS
        val ready = word || full
        if (!ready) return raw.copy(boosterReady = false, activateWord = word && raw.activateWord)
        if (raw.activateWord && raw.boosterReady) return raw
        return raw.copy(
            activateWord = word || raw.activateWord,
            activate = "yes",
            boosterReady = true,
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
        bars.clear()
    }

    companion object {
        const val WINDOW = 5
        const val FULL_HITS = 3
    }
}
