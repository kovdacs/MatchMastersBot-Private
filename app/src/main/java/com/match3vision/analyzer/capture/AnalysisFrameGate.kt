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
 * - While [bubbleLoopRunning] → **always accept** live frames: the floating bubble
 *   is small and must not freeze analysis forever at 0 frames. Prefer hiding the
 *   large analyzer Activity during FUT (moveTaskToBack / compact UI).
 * - While [bubbleOverlayOnly] and never accepted a frame → accept live so a
 *   bubble-only overlay cannot leave the gate stuck at “Fogadott=0”.
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

    /** True while auto-play bubble loop is RUNNING (INDÍTÁS on bubble). */
    @Volatile
    var bubbleLoopRunning: Boolean = false
        private set

    /**
     * True while the small floating bubble is visible (Activity should be
     * backgrounded / compact). Used to avoid perpetual 0-frame freeze.
     */
    @Volatile
    var bubbleOverlayOnly: Boolean = false
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

    fun setBubbleLoopRunning(running: Boolean) {
        bubbleLoopRunning = running
    }

    fun setBubbleOverlayOnly(overlayOnly: Boolean) {
        bubbleOverlayOnly = overlayOnly
    }

    /** True when a newly captured frame should replace the analysis bitmap. */
    fun shouldAcceptLiveFrame(): Boolean {
        if (forceAcceptLive) return true
        if (bubbleLoopRunning) return true
        // Bubble visible, never got a board frame — do not freeze forever at 0.
        if (bubbleOverlayOnly && acceptedFrameCount == 0L) return true
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
        bubbleLoopRunning -> "ÉLŐ (buborék FUT — kis overlay)"
        bubbleOverlayOnly && acceptedFrameCount == 0L ->
            "ÉLŐ (buborék — első képkocka; nagy UI takarás kerülendő)"
        analyzerUiForeground -> "FAGYASZTVA (utolsó tábla-kép; UI takarja a rögzítést)"
        else -> "ÉLŐ (elemző a háttérben — tábla rögzítve)"
    }
}
