package com.match3vision.analyzer.overlay

import com.match3vision.analyzer.input.AutoPlayController

/**
 * Pure sticky-STOP display rules for bubble diagnostics.
 * Prevents stale "ACCESSIBILITY: DISCONNECTED" from surviving into RUNNING.
 */
object DiagnosticsStopDisplay {
    fun resolve(
        mode: AutoPlayController.Mode,
        previous: String?,
        explicit: String?,
        clear: Boolean,
        traceLast: String?,
    ): String? {
        if (clear) return null
        if (explicit != null) return explicit
        if (mode == AutoPlayController.Mode.RUNNING) return null
        return previous ?: traceLast
    }
}
