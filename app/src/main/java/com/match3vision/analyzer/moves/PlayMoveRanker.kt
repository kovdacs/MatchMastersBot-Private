package com.match3vision.analyzer.moves

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.evaluation.MoveEvaluation
import com.match3vision.analyzer.rules.GravityEngine
import com.match3vision.analyzer.rules.MatchDetector
import com.match3vision.analyzer.vision.SpecialType
import com.match3vision.analyzer.vision.TileColor

/**
 * Real-play ordering for a recognized 7×7 board.
 *
 * Extra move, confirmed by the owner: the swap's initial match clears 4 or
 * more gems of one color in one connected group. The shape does not matter
 * (straight line, L, T, plus, or any other orthogonal connection). A later
 * cascade does not grant the extra-move flag.
 *
 * Blue gems cleared, then total gems cleared, include every deterministic
 * cascade step. Unknown refills are not simulated and are never counted as
 * matches. Moves that touch a special gem are not selected. Specials already
 * on the board are named in the export only.
 */
class PlayMoveRanker(
    private val generator: LegalMoveGenerator = LegalMoveGenerator(),
    private val detector: MatchDetector = MatchDetector(),
    private val gravity: GravityEngine = GravityEngine(),
) {
    data class Candidate(
        val move: Move,
        val matchLen: Int,
        val extraMove: Boolean,
        val blueCleared: Int,
        val totalCleared: Int,
        val cascadeSteps: Int,
        val uncertain: Boolean,
        val specials: String,
        val lowerRow: Int,
    ) {
        fun line(): String =
            "matchLen=$matchLen extraMove=${yes(extraMove)} blueCleared=$blueCleared " +
                "totalCleared=$totalCleared cascadeSteps=$cascadeSteps " +
                "uncertain=${yes(uncertain)} specials=$specials lowerRow=$lowerRow"

        /** Finite evaluation so the existing gesture gate can dispatch this move. */
        fun toEvaluation(): MoveEvaluation {
            val ev = (if (extraMove) 1_000f else 0f) + blueCleared * 10f + totalCleared.toFloat()
            return MoveEvaluation(
                move = move,
                totalScore = ev,
                matchScore = totalCleared.toFloat(),
                cascadeScore = cascadeSteps.toFloat(),
                specialScore = 0f,
                starScore = blueCleared.toFloat(),
                boosterScore = 0f,
                futureScore = 0f,
                riskPenalty = 0f,
                expectedValue = ev,
                confidence = 1f,
                uncertain = uncertain,
                reasons = listOf(line()),
                extraMove = if (extraMove) 1f else 0f,
                maxMatchSize = matchLen,
            )
        }

        private fun yes(value: Boolean) = if (value) "yes" else "no"
    }

    data class Ranking(
        val ordered: List<Candidate>,
        val boardSpecials: String,
    ) {
        val top3: List<Candidate> get() = ordered.take(3)

        fun export(): String = buildString {
            appendLine(EXTRA_MOVE_RULE)
            appendLine("refill=not simulated; unknown refills are not counted as matches")
            appendLine("boosters=not used; specials are reported only")
            appendLine("boardSpecials=$boardSpecials")
            if (top3.isEmpty()) {
                appendLine("playTop3: none")
            } else {
                top3.forEachIndexed { index, candidate ->
                    appendLine("play#${index + 1} ${candidate.move} ${candidate.line()}")
                }
            }
        }
    }

    fun rank(board: Board): Ranking {
        val specials = boardSpecials(board)
        val ordered = generator.generate(board)
            .filter { !touchesSpecial(board, it) }
            .map { score(board, it, specials) }
            .sortedWith(ORDER)
        return Ranking(ordered, specials)
    }

    private fun score(board: Board, move: Move, specials: String): Candidate {
        val swapped = board.swapCopy(move.r1, move.c1, move.r2, move.c2)
        val initial = detector.findMatches(swapped)
        val (matchLen, extraMove) = connectedClear(initial)
        val tally = tally(swapped)
        return Candidate(
            move = move,
            matchLen = matchLen,
            extraMove = extraMove,
            blueCleared = tally.blue,
            totalCleared = tally.total,
            cascadeSteps = tally.steps,
            uncertain = tally.uncertain,
            specials = specials,
            lowerRow = maxOf(move.r1, move.r2),
        )
    }

    /**
     * Largest orthogonally connected set of one color inside the initial
     * match groups. Overlapping line groups (L, T, plus) join at the shared cell.
     */
    private fun connectedClear(groups: List<MatchDetector.MatchGroup>): Pair<Int, Boolean> {
        if (groups.isEmpty()) return 0 to false
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
                    for ((dr, dc) in ORTHOGONAL) {
                        val next = (row + dr) to (col + dc)
                        if (next in cells && seen.add(next)) queue.add(next)
                    }
                }
                if (size > best) best = size
            }
        }
        return best to (best >= EXTRA_MOVE_MIN)
    }

    private data class Tally(
        val blue: Int,
        val total: Int,
        val steps: Int,
        val uncertain: Boolean,
    )

    /** Clear and drop known gems. Holes stay unknown and cannot match. */
    private fun tally(start: Board): Tally {
        var board = start
        var blue = 0
        var total = 0
        var steps = 0
        var uncertain = start.unknownCount() > 0
        while (steps < MAX_STEPS) {
            val matches = detector.findMatches(board)
            if (matches.isEmpty()) break
            val cells = matches.flatMap { it.cells }.toSet()
            if (adjacentUnknown(board, cells)) uncertain = true
            for ((row, col) in cells) {
                if (board.get(row, col).color == TileColor.B) blue++
                total++
            }
            board = gravity.apply(gravity.clearCells(board, cells)).board
            steps++
        }
        return Tally(blue, total, steps, uncertain)
    }

    private fun adjacentUnknown(board: Board, cells: Set<Pair<Int, Int>>): Boolean {
        for ((row, col) in cells) {
            for ((dr, dc) in ORTHOGONAL) {
                val nextRow = row + dr
                val nextCol = col + dc
                if (nextRow !in 0 until Board.SIZE || nextCol !in 0 until Board.SIZE) continue
                val tile = board.get(nextRow, nextCol)
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

    private fun boardSpecials(board: Board): String {
        val found = ArrayList<String>()
        board.forEachTile { tile ->
            if (tile.special != SpecialType.NONE) {
                found += "${tile.row},${tile.col}:${tile.special.name}"
            }
        }
        return if (found.isEmpty()) "none" else found.joinToString(",")
    }

    companion object {
        const val EXTRA_MOVE_MIN = 4
        const val EXTRA_MOVE_RULE =
            "extraMoveRule=confirmed by the owner: a swap grants an extra move when its " +
                "initial connected group of one color clears 4 or more gems, in any shape " +
                "(line, L, T, plus, or other connected combination). Later cascades do not " +
                "grant the extra-move flag."

        private const val MAX_STEPS = 32
        private val ORTHOGONAL = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)
        private val ORDER = compareByDescending<Candidate> { it.extraMove }
            .thenByDescending { it.blueCleared }
            .thenByDescending { it.totalCleared }
            .thenByDescending { it.lowerRow }
            .thenBy { it.move.toString() }

        val EMPTY_RANKING = Ranking(emptyList(), "none")
    }
}
