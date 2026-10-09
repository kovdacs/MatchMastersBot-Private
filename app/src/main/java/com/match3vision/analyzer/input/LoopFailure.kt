package com.match3vision.analyzer.input

import kotlinx.coroutines.CancellationException

/**
 * A loop that is cancelled because the user pressed STOP is not a failure.
 * [reasonOrNull] returns null for that cancellation so the caller rethrows
 * it and does not write HIBA.
 */
object LoopFailure {
    fun reasonOrNull(t: Throwable): String? {
        if (t is CancellationException) return null
        val message = t.message ?: t.javaClass.simpleName
        return "HIBA: $message"
    }
}
