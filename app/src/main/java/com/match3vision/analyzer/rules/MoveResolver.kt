package com.match3vision.analyzer.rules

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.vision.SpecialType

class MoveResolver(
    private val swapValidator: SwapValidator = SwapValidator(),
    private val cascadeEngine: CascadeEngine = CascadeEngine(doRefill = false),
    private val specialCreator: SpecialCreator = SpecialCreator(),
    private val comboResolver: SpecialCombinationResolver = SpecialCombinationResolver(),
) {
    data class ResolvedMove(
        val legal: Boolean,
        val uncertain: Boolean,
        val boardAfter: Board,
        val cascade: CascadeEngine.CascadeResult?,
        val combo: SpecialCombinationResolver.Resolution?,
        val reason: String,
    )

    fun resolve(board: Board, r1: Int, c1: Int, r2: Int, c2: Int): ResolvedMove {
        val check = swapValidator.validate(board, r1, c1, r2, c2)
        if (!check.legal) {
            return ResolvedMove(false, check.uncertain, board, null, null, check.reason)
        }
        val t1 = board.get(r1, c1)
        val t2 = board.get(r2, c2)
        val combo = if (t1.special != SpecialType.NONE || t2.special != SpecialType.NONE) {
            comboResolver.resolve(t1.special, t2.special)
        } else null
        var swapped = board.swapCopy(r1, c1, r2, c2)
        if (combo != null && !combo.uncertain) {
            swapped = applyComboClear(swapped, r1, c1, r2, c2, combo.effect)
        }
        val cascade = cascadeEngine.run(swapped)
        specialCreator.fromMatchGroups(MatchDetector().findMatches(board.swapCopy(r1, c1, r2, c2)))
        return ResolvedMove(
            legal = true,
            uncertain = check.uncertain || cascade.uncertain || (combo?.uncertain == true),
            boardAfter = cascade.board,
            cascade = cascade,
            combo = combo,
            reason = "resolved",
        )
    }

    private fun applyComboClear(
        board: Board, r1: Int, c1: Int, r2: Int, c2: Int,
        effect: SpecialCombinationResolver.ComboEffect,
    ): Board {
        val cells = mutableSetOf<Pair<Int, Int>>()
        fun addRow(r: Int) { for (c in 0 until Board.SIZE) cells += r to c }
        fun addCol(c: Int) { for (r in 0 until Board.SIZE) cells += r to c }
        fun addArea(cr: Int, cc: Int, rad: Int) {
            for (r in (cr - rad)..(cr + rad)) for (c in (cc - rad)..(cc + rad)) {
                if (r in 0 until Board.SIZE && c in 0 until Board.SIZE) cells += r to c
            }
        }
        when (effect) {
            SpecialCombinationResolver.ComboEffect.CLEAR_ROW -> addRow(r1)
            SpecialCombinationResolver.ComboEffect.CLEAR_COL -> addCol(c1)
            SpecialCombinationResolver.ComboEffect.CLEAR_ROW_AND_COL -> {
                addRow(r1); addCol(c1); addRow(r2); addCol(c2)
            }
            SpecialCombinationResolver.ComboEffect.CLEAR_3X3 -> { addArea(r1, c1, 1); addArea(r2, c2, 1) }
            SpecialCombinationResolver.ComboEffect.CLEAR_5X5 -> { addArea(r1, c1, 2); addArea(r2, c2, 2) }
            SpecialCombinationResolver.ComboEffect.CLEAR_COLOR,
            SpecialCombinationResolver.ComboEffect.BOARD_WIDE -> {
                for (r in 0 until Board.SIZE) for (c in 0 until Board.SIZE) cells += r to c
            }
            else -> {}
        }
        return GravityEngine().clearCells(board, cells)
    }
}
