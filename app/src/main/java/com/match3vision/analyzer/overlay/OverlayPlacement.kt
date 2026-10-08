package com.match3vision.analyzer.overlay

/**
 * Where the floating bubble sits during capture, and whether that rect
 * covers the board.
 *
 * Analysis must not run a cycle while the overlay intersects the board ROI.
 * That hold is a safety gate. It does not change vision thresholds and it
 * does not dispatch.
 */
object OverlayPlacement {
    const val COLLAPSED_WIDTH_DP = 156f
    const val COLLAPSED_HEIGHT_DP = 96f
    const val COLLAPSED_TOP_DP = 28f
    const val COLLAPSED_END_MARGIN_DP = 8f

    /**
     * Wait after collapsing before a captured frame is eligible.
     * MediaProjection delivers the composed screen; a frame already in hand
     * still shows the expanded panel.
     */
    const val MIN_POST_COLLAPSE_MS = 500L

    data class Rect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        fun width(): Int = right - left
        fun height(): Int = bottom - top

        /** Shared interior. A shared edge is not an intersection. */
        fun intersects(other: Rect): Boolean {
            return left < other.right && right > other.left &&
                top < other.bottom && bottom > other.top
        }
    }

    /**
     * Small chip in the top-end corner, above a portrait board that starts
     * near mid-screen. The caller still has to run [OverlayBoardGate] once
     * the real ROI is known — this rect is the placement, not a proof.
     */
    fun collapsedChipPx(screenWidth: Int, screenHeight: Int, density: Float): Rect {
        val d = if (density > 0f) density else 1f
        val width = (COLLAPSED_WIDTH_DP * d).toInt().coerceAtLeast(1)
        val height = (COLLAPSED_HEIGHT_DP * d).toInt().coerceAtLeast(1)
        val top = (COLLAPSED_TOP_DP * d).toInt().coerceAtLeast(0)
        val margin = (COLLAPSED_END_MARGIN_DP * d).toInt().coerceAtLeast(0)
        val right = (screenWidth - margin).coerceAtLeast(1)
        val left = (right - width).coerceAtLeast(0)
        val bottom = (top + height).coerceAtMost(screenHeight.coerceAtLeast(top + 1))
        return Rect(left, top, right.coerceAtLeast(left + 1), bottom.coerceAtLeast(top + 1))
    }

    /**
     * A frame received before the collapse has been on screen still contains
     * the expanded panel. [collapseWallMs] of 0 means collapse has not happened.
     */
    fun frameShowsCollapsedOverlay(
        frameTimestampMs: Long,
        collapseWallMs: Long,
        minDelayMs: Long = MIN_POST_COLLAPSE_MS,
    ): Boolean {
        if (collapseWallMs <= 0L) return false
        return frameTimestampMs >= collapseWallMs + minDelayMs
    }
}

object OverlayBoardGate {
    const val HOLD_INTERSECTS = "HOLD: overlay intersects board ROI — no touch"
    const val HOLD_UNKNOWN = "HOLD: overlay bounds unknown — no touch"
    const val HOLD_UNMAPPED = "HOLD: overlay and board are not in one pixel grid — no touch"

    data class Decision(
        val allowAnalysis: Boolean,
        val reason: String,
    )

    fun evaluate(
        overlay: OverlayPlacement.Rect?,
        boardLeft: Int,
        boardTop: Int,
        boardRight: Int,
        boardBottom: Int,
        frameWidth: Int,
        frameHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        rotation: Int,
    ): Decision {
        if (overlay == null) {
            return Decision(false, HOLD_UNKNOWN)
        }
        if (frameWidth <= 0 || frameHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) {
            return Decision(false, HOLD_UNMAPPED)
        }
        // Rotation 0 is the only case measured on the Test 0 phone.
        // Unknown (-1) and other rotations are not a shared origin.
        if (rotation != 0) {
            return Decision(false, HOLD_UNMAPPED)
        }
        if (frameWidth != screenWidth || frameHeight != screenHeight) {
            return Decision(false, HOLD_UNMAPPED)
        }
        val board = OverlayPlacement.Rect(boardLeft, boardTop, boardRight, boardBottom)
        if (overlay.intersects(board)) {
            return Decision(false, HOLD_INTERSECTS)
        }
        return Decision(true, "overlay outside board ROI")
    }
}
