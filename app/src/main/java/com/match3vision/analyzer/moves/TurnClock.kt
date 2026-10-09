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
    /** "Time Left: N" at or below this many seconds uses the fast scorer. */
    const val LOW_TIME_LEFT_SECONDS = 3

    private val remaining = Regex("remainingMs=(-?\\d+)")
    private val timeLeft = Regex("""Time Left:\s*(\d+)""")

    fun skipLookahead(timer: String): Boolean {
        val millis = remaining.find(timer)?.groupValues?.get(1)?.toLongOrNull()
        if (millis != null) return millis in 0 until LOW_REMAINING_MS
        val seconds = timeLeft.find(timer)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return seconds in 0..LOW_TIME_LEFT_SECONDS
    }
}
