package com.match3vision.analyzer.rules

import com.match3vision.analyzer.vision.SpecialType
import com.match3vision.analyzer.vision.TileColor

/**
 * Generic special creation (ASSUMPTIONS — not Match Masters facts):
 * length 4 → TWO_WAY_ARROW, length 5+ → LIGHTNING, 2×2 → BOMB.
 */
class SpecialCreator(private val enableSquareBomb: Boolean = true) {
    data class Creation(val row: Int, val col: Int, val special: SpecialType)

    fun fromMatchGroups(groups: List<MatchDetector.MatchGroup>): List<Creation> {
        val out = mutableListOf<Creation>()
        for (g in groups) {
            val special = when {
                g.size >= 5 -> SpecialType.LIGHTNING
                g.size == 4 -> SpecialType.TWO_WAY_ARROW
                else -> null
            }
            if (special != null) {
                val (r, c) = g.cells[g.cells.size / 2]
                out += Creation(r, c, special)
            }
        }
        return out
    }

    fun findSquareBombs(colorAt: (Int, Int) -> TileColor?): List<Creation> {
        if (!enableSquareBomb) return emptyList()
        val out = mutableListOf<Creation>()
        for (r in 0 until 6) for (c in 0 until 6) {
            val a = colorAt(r, c) ?: continue
            if (a == TileColor.UNKNOWN) continue
            if (colorAt(r, c + 1) == a && colorAt(r + 1, c) == a && colorAt(r + 1, c + 1) == a) {
                out += Creation(r, c, SpecialType.BOMB)
            }
        }
        return out
    }
}
