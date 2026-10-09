package com.match3vision.analyzer.moves

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.rules.GravityEngine
import com.match3vision.analyzer.rules.MatchDetector
import com.match3vision.analyzer.vision.SpecialType
import com.match3vision.analyzer.vision.TileColor

/**
 * Solo-only resolution. Plain matches leave the owner-confirmed special on the
 * swap cell. Swapping a special fires that one effect. Unknown refills are
 * not simulated and never match. Two-special combos are the union of the two
 * single effects and are marked uncertain — combo bonuses are not invented.
 */
internal class SoloSwap(
    private val generator: LegalMoveGenerator = LegalMoveGenerator(),
    private val detector: MatchDetector = MatchDetector(),
    private val gravity: GravityEngine = GravityEngine(),
) {
    data class Outcome(
        val board: Board,
        val matchLen: Int,
        val extraMove: Boolean,
        val blue: Int,
        val red: Int,
        val total: Int,
        val steps: Int,
        val uncertain: Boolean,
        val counts: Map<TileColor, Int>,
        val specialSpawn: String,
    )

    fun moves(board: Board): List<Move> {
        val plain = generator.generate(board).filter { !touchesSpecial(board, it) }
        val specials = ArrayList<Move>()
        for (r in 0 until Board.SIZE) {
            for (c in 0 until Board.SIZE) {
                for ((dr, dc) in listOf(0 to 1, 1 to 0)) {
                    val r2 = r + dr
                    val c2 = c + dc
                    if (r2 !in 0 until Board.SIZE || c2 !in 0 until Board.SIZE) continue
                    val a = board.get(r, c)
                    val b = board.get(r2, c2)
                    if (a.isUnknown || b.isUnknown) continue
                    if (a.special == SpecialType.NONE && b.special == SpecialType.NONE) continue
                    specials += Move(r, c, r2, c2).normalized()
                }
            }
        }
        return (plain + specials).distinctBy { it.normalized() }
    }

    fun resolve(board: Board, move: Move): Outcome {
        val left = board.get(move.r1, move.c1)
        val right = board.get(move.r2, move.c2)
        val swapped = board.swapCopy(move.r1, move.c1, move.r2, move.c2)
        return if (left.special != SpecialType.NONE || right.special != SpecialType.NONE) {
            activate(swapped, move, left, right)
        } else {
            match(swapped, move)
        }
    }

    private fun match(swapped: Board, move: Move): Outcome {
        val counts = HashMap<TileColor, Int>()
        var current = swapped
        var steps = 0
        var uncertain = swapped.unknownCount() > 0
        var matchLen = 0
        var extra = false
        var spawnName = "none"
        var first = true
        while (steps < MAX_STEPS) {
            val groups = detector.findMatches(current)
            if (groups.isEmpty()) break
            val cells = groups.flatMap { it.cells }.toSet()
            if (adjacentUnknown(current, cells)) uncertain = true
            val spawn = if (first) MatchShape.spawnFor(groups) else null
            if (first) {
                matchLen = MatchShape.connectedSize(groups)
                extra = matchLen >= PlayMoveRanker.EXTRA_MOVE_MIN
                spawnName = spawn?.name ?: "none"
                first = false
            }
            val keep = if (spawn != null) spawnCell(move, cells) else null
            val clearing = if (keep != null && keep in cells) cells - keep else cells
            addCounts(current, clearing, counts)
            current = gravity.clearCells(current, clearing)
            if (keep != null && spawn != null && keep in cells) {
                val host = current.get(keep.first, keep.second)
                current = current.setCopy(
                    keep.first,
                    keep.second,
                    host.copy(special = spawn.type, starValue = spawn.axis, visible = true),
                )
            }
            current = gravity.apply(current).board
            steps++
        }
        return outcome(current, matchLen, extra, steps, uncertain, counts, spawnName)
    }

    private fun activate(
        swapped: Board,
        move: Move,
        leftOrig: com.match3vision.analyzer.board.Tile,
        rightOrig: com.match3vision.analyzer.board.Tile,
    ): Outcome {
        val counts = HashMap<TileColor, Int>()
        val cells = HashSet<Pair<Int, Int>>()
        var uncertain = swapped.unknownCount() > 0 ||
            (leftOrig.special != SpecialType.NONE && rightOrig.special != SpecialType.NONE)
        // After the swap, the tile from (r1,c1) sits at (r2,c2).
        if (leftOrig.special != SpecialType.NONE) {
            addEffect(cells, move.r2, move.c2, leftOrig.special, leftOrig.starValue, rightOrig.color, swapped)
            if (leftOrig.starValue != MatchShape.AXIS_ROW && leftOrig.starValue != MatchShape.AXIS_COL &&
                leftOrig.special == SpecialType.TWO_WAY_ARROW
            ) {
                uncertain = true
            }
        }
        if (rightOrig.special != SpecialType.NONE) {
            addEffect(cells, move.r1, move.c1, rightOrig.special, rightOrig.starValue, leftOrig.color, swapped)
            if (rightOrig.starValue != MatchShape.AXIS_ROW && rightOrig.starValue != MatchShape.AXIS_COL &&
                rightOrig.special == SpecialType.TWO_WAY_ARROW
            ) {
                uncertain = true
            }
        }
        addCounts(swapped, cells, counts)
        var current = gravity.apply(gravity.clearCells(swapped, cells)).board
        var steps = 1
        while (steps < MAX_STEPS) {
            val groups = detector.findMatches(current)
            if (groups.isEmpty()) break
            val matched = groups.flatMap { it.cells }.toSet()
            if (adjacentUnknown(current, matched)) uncertain = true
            addCounts(current, matched, counts)
            current = gravity.apply(gravity.clearCells(current, matched)).board
            steps++
        }
        val name = listOf(leftOrig.special, rightOrig.special)
            .filter { it != SpecialType.NONE }
            .joinToString("+") { it.name }
        return outcome(current, 0, false, steps, uncertain, counts, "activate:$name")
    }

    private fun addEffect(
        cells: MutableSet<Pair<Int, Int>>,
        row: Int,
        col: Int,
        special: SpecialType,
        axis: Int,
        partner: TileColor,
        board: Board,
    ) {
        when (special) {
            SpecialType.TWO_WAY_ARROW -> if (axis == MatchShape.AXIS_COL) {
                for (r in 0 until Board.SIZE) cells += r to col
            } else {
                for (c in 0 until Board.SIZE) cells += row to c
            }
            SpecialType.BOMB -> {
                for (r in (row - 1)..(row + 1)) {
                    for (c in (col - 1)..(col + 1)) {
                        if (r in 0 until Board.SIZE && c in 0 until Board.SIZE) cells += r to c
                    }
                }
            }
            SpecialType.LIGHTNING -> {
                if (partner == TileColor.UNKNOWN) {
                    cells += row to col
                } else {
                    for (r in 0 until Board.SIZE) {
                        for (c in 0 until Board.SIZE) {
                            if (board.get(r, c).color == partner) cells += r to c
                        }
                    }
                }
            }
            SpecialType.NONE -> Unit
        }
    }

    private fun spawnCell(move: Move, cells: Set<Pair<Int, Int>>): Pair<Int, Int>? {
        val second = move.r2 to move.c2
        val first = move.r1 to move.c1
        return when {
            second in cells -> second
            first in cells -> first
            else -> null
        }
    }

    private fun addCounts(board: Board, cells: Set<Pair<Int, Int>>, counts: MutableMap<TileColor, Int>) {
        for ((row, col) in cells) {
            val color = board.get(row, col).color
            if (color == TileColor.UNKNOWN) continue
            counts[color] = (counts[color] ?: 0) + 1
        }
    }

    private fun adjacentUnknown(board: Board, cells: Set<Pair<Int, Int>>): Boolean {
        for ((row, col) in cells) {
            for ((dr, dc) in listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)) {
                val r = row + dr
                val c = col + dc
                if (r !in 0 until Board.SIZE || c !in 0 until Board.SIZE) continue
                val tile = board.get(r, c)
                if (tile.color == TileColor.UNKNOWN || !tile.visible) return true
            }
        }
        return false
    }

    private fun touchesSpecial(board: Board, move: Move): Boolean {
        val left = board.get(move.r1, move.c1).special
        val right = board.get(move.r2, move.c2).special
        return left != SpecialType.NONE || right != SpecialType.NONE
    }

    private fun outcome(
        board: Board,
        matchLen: Int,
        extra: Boolean,
        steps: Int,
        uncertain: Boolean,
        counts: Map<TileColor, Int>,
        spawn: String,
    ): Outcome {
        val blue = counts[TileColor.B] ?: 0
        val red = counts[TileColor.R] ?: 0
        val total = counts.values.sum()
        return Outcome(board, matchLen, extra, blue, red, total, steps, uncertain, counts, spawn)
    }

    companion object {
        private const val MAX_STEPS = 32
    }
}
