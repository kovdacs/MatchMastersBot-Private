package com.match3vision.analyzer.moves

/**
 * Turn timer, as text already read from the HUD.
 *
 * This does not OCR. The production reader logs [NOT_DETECTABLE] when the
 * digits cannot be read. A unit test may pass `remainingMs=<n>` to prove the
 * low-time fallback. Anything else, including a missing number, keeps lookahead.
 */
object TurnClock {
    const val NOT_DETECTABLE = "not detectable"
    const val LOW_REMAINING_MS = 15_000L

    private val remaining = Regex("remainingMs=(-?\\d+)")

    fun skipLookahead(timer: String): Boolean {
        val match = remaining.find(timer) ?: return false
        val millis = match.groupValues[1].toLongOrNull() ?: return false
        return millis in 0 until LOW_REMAINING_MS
    }
}
