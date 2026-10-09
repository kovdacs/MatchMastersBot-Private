package com.match3vision.analyzer.moves

import com.match3vision.analyzer.rules.MatchDetector
import com.match3vision.analyzer.vision.SpecialType

/**
 * Owner-confirmed solo shapes. Not used for PvP.
 *
 * 4 in a straight line → arrow along that line (row or column).
 * 5 in an L, T, or plus → bomb (3×3 when activated).
 * 5 in a straight line → color bomb (clears the swapped color).
 * A connected group of 4 or more is an extra move. Later cascades are not.
 */
internal object MatchShape {
    private val orthogonal = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)

    fun connectedSize(groups: List<MatchDetector.MatchGroup>): Int {
        if (groups.isEmpty()) return 0
        var best = 0
        for ((_, colorGroups) in groups.groupBy { it.color }) {
            val cells = colorGroups.flatMap { it.cells }.toSet()
            val seen = HashSet<Pair<Int, Int>>()
            for (start in cells) {
                if (!seen.add(start)) continue
                var size = 0
                val queue = ArrayDeque<Pair<Int, Int>>()
                queue.add(start)
                while (queue.isNotEmpty()) {
                    val (row, col) = queue.removeFirst()
                    size++
                    for ((dr, dc) in orthogonal) {
                        val next = (row + dr) to (col + dc)
                        if (next in cells && seen.add(next)) queue.add(next)
                    }
                }
                if (size > best) best = size
            }
        }
        return best
    }

    data class Spawn(val type: SpecialType, val axis: Int, val name: String)

    /** Null when the initial groups are not an extra-move shape. */
    fun spawnFor(groups: List<MatchDetector.MatchGroup>): Spawn? {
        if (connectedSize(groups) < PlayMoveRanker.EXTRA_MOVE_MIN) return null
        val line5 = groups.firstOrNull { it.size >= 5 }
        if (line5 != null) {
            return Spawn(SpecialType.LIGHTNING, 0, "LIGHTNING")
        }
        val hasRow = groups.any { it.horizontal && it.size >= 3 }
        val hasCol = groups.any { !it.horizontal && it.size >= 3 }
        if (hasRow && hasCol) {
            return Spawn(SpecialType.BOMB, 0, "BOMB")
        }
        val line4 = groups.firstOrNull { it.size == 4 } ?: return null
        return if (line4.horizontal) {
            Spawn(SpecialType.TWO_WAY_ARROW, AXIS_ROW, "ARROW-row")
        } else {
            Spawn(SpecialType.TWO_WAY_ARROW, AXIS_COL, "ARROW-col")
        }
    }

    const val AXIS_ROW = 1
    const val AXIS_COL = 2
}
