package com.match3vision.analyzer.rules

import com.match3vision.analyzer.board.Board
import kotlin.math.max

/**
 * Resolve match → (optional special leave-behind) → clear → gravity loops.
 * ASSUMPTION: column gravity; specials from [SpecialCreator] (generic match-3).
 * Analyzer only — never actuates input.
 */
class CascadeEngine(
    private val matchDetector: MatchDetector = MatchDetector(),
    private val gravity: GravityEngine = GravityEngine(),
    private val refill: BoardRefill = BoardRefill(),
    private val specialCreator: SpecialCreator = SpecialCreator(),
    private val maxSteps: Int = 32,
    private val doRefill: Boolean = false,
    private val placeSpecials: Boolean = true,
) {
    data class CascadeResult(
        val board: Board,
        val steps: Int,
        val clearedCells: Int,
        val uncertain: Boolean,
        val matchGroupsTotal: Int,
        val maxMatchSize: Int = 0,
        val concurrentMatchesFirstStep: Int = 0,
        val specialsCreated: Int = 0,
        val extraMoveLikely: Boolean = false,
    )

    fun run(initial: Board): CascadeResult {
        var board = initial
        var steps = 0
        var cleared = 0
        var uncertain = initial.unknownCount() > 0
        var groupsTotal = 0
        var maxMatch = 0
        var concurrentFirst = 0
        var specialsCreated = 0
        while (steps < maxSteps) {
            val matches = matchDetector.findMatches(board)
            if (matches.isEmpty()) break
            if (steps == 0) {
                concurrentFirst = matches.size
            }
            maxMatch = max(maxMatch, matches.maxOf { it.size })
            groupsTotal += matches.size
            val cells = matches.flatMap { it.cells }.toSet()
            cleared += cells.size

            val creations = if (placeSpecials) specialCreator.fromMatchGroups(matches) else emptyList()
            val preserved = creations.map { cr ->
                val t = board.get(cr.row, cr.col)
                Triple(cr.row, cr.col, t.copy(special = cr.special, visible = true))
            }

            board = gravity.clearCells(board, cells)
            for ((r, c, tile) in preserved) {
                board = board.setCopy(r, c, tile.copy(row = r, col = c))
                specialsCreated++
            }
            board = gravity.apply(board).board
            if (doRefill) {
                val rr = refill.refill(board)
                board = rr.board
                if (rr.uncertain) uncertain = true
            }
            steps++
            if (board.unknownCount() > 0) uncertain = true
        }
        val extraMove = maxMatch >= 5 || specialsCreated > 0 || steps >= 2
        return CascadeResult(
            board = board,
            steps = steps,
            clearedCells = cleared,
            uncertain = uncertain,
            matchGroupsTotal = groupsTotal,
            maxMatchSize = maxMatch,
            concurrentMatchesFirstStep = concurrentFirst,
            specialsCreated = specialsCreated,
            extraMoveLikely = extraMove,
        )
    }
}
