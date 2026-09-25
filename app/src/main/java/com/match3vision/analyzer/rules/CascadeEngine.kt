package com.match3vision.analyzer.rules

import com.match3vision.analyzer.board.Board

class CascadeEngine(
    private val matchDetector: MatchDetector = MatchDetector(),
    private val gravity: GravityEngine = GravityEngine(),
    private val refill: BoardRefill = BoardRefill(),
    private val maxSteps: Int = 32,
    private val doRefill: Boolean = false,
) {
    data class CascadeResult(
        val board: Board,
        val steps: Int,
        val clearedCells: Int,
        val uncertain: Boolean,
        val matchGroupsTotal: Int,
    )

    fun run(initial: Board): CascadeResult {
        var board = initial
        var steps = 0
        var cleared = 0
        var uncertain = initial.unknownCount() > 0
        var groupsTotal = 0
        while (steps < maxSteps) {
            val matches = matchDetector.findMatches(board)
            if (matches.isEmpty()) break
            groupsTotal += matches.size
            val cells = matches.flatMap { it.cells }.toSet()
            cleared += cells.size
            board = gravity.clearCells(board, cells)
            board = gravity.apply(board).board
            if (doRefill) {
                val rr = refill.refill(board)
                board = rr.board
                if (rr.uncertain) uncertain = true
            }
            steps++
            if (board.unknownCount() > 0) uncertain = true
        }
        return CascadeResult(board, steps, cleared, uncertain, groupsTotal)
    }
}
