package com.match3vision.analyzer.capture

/**
 * Controls which MediaProjection frames are kept for vision / smoke analysis.
 *
 * **Why:** MediaProjection captures the *composed* display, including this app's
 * own UI. When the analyzer Activity is in the foreground, live frames are our
 * Material panel (status-bar letterbox ~LTRB(0,88,…), gridConf can still look
 * high, cells nearly all UNKNOWN → boardConf clamps to 0). Match Masters under
 * that overlay is not visible to the analyzer.
 *
 * **Policy:**
 * - While [analyzerUiForeground] is true → **freeze**: keep the last board frame
 *   captured while the UI was paused (user on Match Masters / split-screen).
 * - While false → **accept** live frames (board should be visible).
 * - Smoke post-swipe wait may temporarily [forceAcceptLive] so a new board
 *   frame can arrive (split-screen / PiP required; fullscreen self-UI still fails).
 *
 * MediaProjection does **not** reliably capture "under" overlays; prefer
 * analyzing a frame taken while our panel does not cover the playfield.
 */
class AnalysisFrameGate {
    @Volatile
    var analyzerUiForeground: Boolean = true
        private set

    @Volatile
    var forceAcceptLive: Boolean = false
        private set

    @Volatile
    var frozenFrameCount: Long = 0
        private set

    @Volatile
    var acceptedFrameCount: Long = 0
        private set

    @Volatile
    var discardedWhileFrozen: Long = 0
        private set

    fun setAnalyzerUiForeground(foreground: Boolean) {
        analyzerUiForeground = foreground
        if (foreground) frozenFrameCount++
    }

    fun setForceAcceptLive(force: Boolean) {
        forceAcceptLive = force
    }

    /** True when a newly captured frame should replace the analysis bitmap. */
    fun shouldAcceptLiveFrame(): Boolean {
        if (forceAcceptLive) return true
        return !analyzerUiForeground
    }

    fun onFrameOffered(accepted: Boolean) {
        if (accepted) {
            acceptedFrameCount++
        } else {
            discardedWhileFrozen++
        }
    }

    fun statusText(): String = when {
        forceAcceptLive -> "ÉLŐ (próba várakozás — tábla legyen látható)"
        analyzerUiForeground -> "FAGYASZTVA (utolsó tábla-kép; UI takarja a rögzítést)"
        else -> "ÉLŐ (elemző a háttérben — tábla rögzítve)"
    }
}
