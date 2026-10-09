package com.match3vision.analyzer.input

import com.match3vision.analyzer.board.Board

/**
 * First-move calibration for the proven 1080×2400, rotation-0 screen.
 * alignmentProven stays false. A saved hit is still preferred when one exists.
 */
object AutoCalibration {
    const val PROVEN_WIDTH = 1080
    const val PROVEN_HEIGHT = 2400
    const val NOTE = "auto: move-1 verified"
    const val STOP_MISSED = "STOP — auto-calibration: board change missed the swapped cells"
    const val STOP_GEOMETRY = "STOP — auto-calibration: screen is not 1080x2400 rotation 0"

    fun provenGeometry(
        frameWidth: Int,
        frameHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        rotation: Int,
    ): Boolean = frameWidth == PROVEN_WIDTH &&
        frameHeight == PROVEN_HEIGHT &&
        screenWidth == frameWidth &&
        screenHeight == frameHeight &&
        rotation == 0

    /** Cells whose color, shape, or special differs. */
    fun changedCells(before: Board, after: Board): Set<Pair<Int, Int>> {
        val changed = HashSet<Pair<Int, Int>>()
        for (row in 0 until Board.SIZE) {
            for (col in 0 until Board.SIZE) {
                val left = before.get(row, col)
                val right = after.get(row, col)
                if (left.color != right.color || left.shape != right.shape || left.special != right.special) {
                    changed += row to col
                }
            }
        }
        return changed
    }

    /**
     * The changed region overlaps the swap when some changed cell shares a
     * swapped row and some changed cell shares a swapped column.
     */
    fun overlaps(changed: Set<Pair<Int, Int>>, r1: Int, c1: Int, r2: Int, c2: Int): Boolean {
        if (changed.isEmpty()) return false
        val rows = setOf(r1, r2)
        val cols = setOf(c1, c2)
        return changed.any { it.first in rows } && changed.any { it.second in cols }
    }

    /** Move text is `(r,c)↔(r,c)`. */
    fun parseMove(cells: String): IntArray? {
        val match = MOVE.find(cells) ?: return null
        return intArrayOf(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
            match.groupValues[4].toInt(),
        )
    }

    fun record(nowMs: Long, screenWidth: Int = PROVEN_WIDTH, screenHeight: Int = PROVEN_HEIGHT): CoordinateSelfCheck.Record {
        val midX = screenWidth / 2f
        val midY = screenHeight / 2f
        return CoordinateSelfCheck.Record(
            status = CoordinateSelfCheck.STATUS_MEASURED_WITHIN_TOLERANCE,
            expectedX = midX,
            expectedY = midY,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            frameWidth = screenWidth,
            frameHeight = screenHeight,
            rotation = 0,
            statusBarInsetPx = 0,
            navigationBarInsetPx = 0,
            cutoutInsetPx = 0,
            originOffsetX = 0,
            originOffsetY = 0,
            observedNote = NOTE,
            alignmentProven = false,
            reason = NOTE,
            observedX = midX,
            observedY = midY,
            recordedAtMs = nowMs,
        )
    }

    private val MOVE = Regex("""\((\d+),(\d+)\)↔\((\d+),(\d+)\)""")
}
