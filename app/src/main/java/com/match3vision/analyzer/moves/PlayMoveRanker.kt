package com.match3vision.analyzer.moves

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.evaluation.MoveEvaluation
import com.match3vision.analyzer.hud.HudObservation
import com.match3vision.analyzer.rules.GravityEngine
import com.match3vision.analyzer.rules.MatchDetector
import com.match3vision.analyzer.vision.SpecialType
import com.match3vision.analyzer.vision.TileColor
import kotlin.math.roundToInt

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
        val redCleared: Int = 0,
        val specialSpawn: String = "none",
        val blueFactor: Double = 1.0,
        /** Points for this ply only. See [plyPoints]. */
        val plyScore: Int = 0,
        val followUp: Move? = null,
        val followUpExtraMove: Boolean = false,
        val followUpBlue: Int = 0,
        val followUpTotal: Int = 0,
        val followUpUncertain: Boolean = false,
        val followWeight: Double = 0.0,
        /** plyScore + followWeight * follow-up ply. Greedy leaves this equal to [plyScore]. */
        val totalScore: Double = 0.0,
    ) {
        fun line(): String = buildString {
            append("matchLen=$matchLen extraMove=${yes(extraMove)} blueCleared=$blueCleared ")
            append("redCleared=$redCleared totalCleared=$totalCleared cascadeSteps=$cascadeSteps ")
            append("uncertain=${yes(uncertain)} specials=$specials spawn=$specialSpawn ")
            append("lowerRow=$lowerRow blueFactor=$blueFactor ply=$plyScore")
            if (followUp != null) {
                append(" followUp=$followUp weight=$followWeight")
                append(" followExtra=${yes(followUpExtraMove)} followBlue=$followUpBlue")
                append(" followTotal=$followUpTotal followUncertain=${yes(followUpUncertain)}")
                append(" score=$totalScore")
            }
        }

        /** Finite evaluation so the existing gesture gate can dispatch this move. */
        fun toEvaluation(): MoveEvaluation {
            val ev = totalScore.toFloat().takeIf { it.isFinite() } ?: plyScore.toFloat()
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
        val decisionMs: Long = 0L,
        /** used, greedy, fallback-budget, or skipped-low-time. */
        val lookahead: String = "greedy",
        val greedyMove: String = "",
        val timer: String = TurnClock.NOT_DETECTABLE,
        val hud: String = HudObservation.UNKNOWN.log(),
    ) {
        val top3: List<Candidate> get() = ordered.take(3)

        fun export(): String = buildString {
            appendLine(EXTRA_MOVE_RULE)
            appendLine(SCORE_FORMULA)
            appendLine("refill=not simulated; unknown refills are not counted as matches")
            appendLine("boosters=not ranked; solo ACTIVATE is a separate default-off tap")
            appendLine("boardSpecials=$boardSpecials")
            appendLine(MODE_GATE)
            appendLine("lookahead=$lookahead decisionMs=$decisionMs budgetMs=$DECISION_BUDGET_MS")
            appendLine("timer=$timer")
            appendLine("hud=$hud")
            val chosen = ordered.firstOrNull()
            val greedy = greedyMove.ifBlank { chosen?.move?.toString() ?: "none" }
            appendLine("greedyPick=$greedy")
            if (chosen == null) {
                appendLine("chosen=none")
            } else {
                val differs = chosen.move.toString() != greedy
                appendLine(
                    "chosen=${chosen.move} differsFromGreedy=${if (differs) "yes" else "no"} " +
                        chosen.line(),
                )
            }
            if (top3.isEmpty()) {
                appendLine("playTop3: none")
            } else {
                top3.forEachIndexed { index, candidate ->
                    appendLine("play#${index + 1} ${candidate.move} ${candidate.line()}")
                }
            }
        }
    }

    /** 0.25.0 order: extra move, blue, total, lower row. No follow-up. */
    fun rank(board: Board): Ranking {
        val specials = boardSpecials(board)
        val ordered = greedyCandidates(board, specials)
        return Ranking(
            ordered = ordered,
            boardSpecials = specials,
            lookahead = "greedy",
            greedyMove = ordered.firstOrNull()?.move?.toString() ?: "none",
        )
    }

    /**
     * Solo two-ply. Other HUDs keep the 0.25.0 order ([lookahead] `skipped-mode`).
     * Past [DECISION_BUDGET_MS], that same order is returned as `fallback-budget`.
     */
    fun rankLookahead(
        board: Board,
        budgetMs: Long = DECISION_BUDGET_MS,
        clock: () -> Long = System::nanoTime,
        hud: HudObservation = HudObservation.UNKNOWN,
    ): Ranking {
        val started = clock()
        fun elapsedMs(): Long = (clock() - started) / 1_000_000L
        val specials = boardSpecials(board)
        val greedy = greedyCandidates(board, specials)
        val greedyMove = greedy.firstOrNull()?.move?.toString() ?: "none"
        val timer = hud.timer
        if (greedy.isEmpty() && !hud.soloLayout) {
            return Ranking(emptyList(), specials, elapsedMs(), "greedy", greedyMove, timer, hud.log())
        }
        val lowTime = TurnClock.skipLookahead(timer)
        val pvpThink = hud.mode == "pvp" && hud.playerTurn() && hud.multiplier != null && !lowTime
        if (!hud.soloLayout && !pvpThink) {
            val why = if (hud.mode == "pvp" && hud.playerTurn() && lowTime) "skipped-low-time" else "skipped-mode"
            return Ranking(greedy, specials, elapsedMs(), why, greedyMove, timer, hud.log())
        }
        if (lowTime) {
            return Ranking(greedy, specials, elapsedMs(), "skipped-low-time", greedyMove, timer, hud.log())
        }
        val solo = SoloSwap(generator, detector, gravity)
        val immediate = solo.moves(board).map { soloCandidate(board, it, specials, hud, solo) }
        if (immediate.isEmpty()) {
            return Ranking(emptyList(), specials, elapsedMs(), "greedy", "none", timer, hud.log())
        }
        val greedySolo = immediate.sortedWith(ORDER)
        val pool = greedySolo.take(LOOKAHEAD_WIDTH)
        val searched = ArrayList<Candidate>(pool.size)
        for (candidate in pool) {
            if (elapsedMs() > budgetMs) {
                return Ranking(greedy, specials, elapsedMs(), "fallback-budget", greedyMove, timer, hud.log())
            }
            val after = solo.resolve(board, candidate.move).board
            val weight = followWeight(candidate.extraMove, hud.movesRemaining)
            val follow = if (weight == 0.0) null else bestSoloFollow(after, specials, hud, solo)
            val followPly = follow?.plyScore ?: 0
            searched += candidate.copy(
                followUp = follow?.move,
                followUpExtraMove = follow?.extraMove == true,
                followUpBlue = follow?.blueCleared ?: 0,
                followUpTotal = follow?.totalCleared ?: 0,
                followUpUncertain = follow?.uncertain == true,
                followWeight = weight,
                totalScore = candidate.plyScore + weight * followPly,
            )
        }
        val ordered = searched.sortedWith(LOOKAHEAD_ORDER) + greedySolo.drop(LOOKAHEAD_WIDTH)
        return Ranking(ordered, specials, elapsedMs(), "used", greedyMove, timer, hud.log())
    }

    fun boardAfter(board: Board, move: Move): Board = resolve(board, move).board

    fun soloAfter(board: Board, move: Move): Board = SoloSwap(generator, detector, gravity).resolve(board, move).board

    private fun greedyCandidates(board: Board, specials: String): List<Candidate> =
        generator.generate(board)
            .filter { !touchesSpecial(board, it) }
            .map { score(board, it, specials) }
            .sortedWith(ORDER)

    private fun bestSoloFollow(
        board: Board,
        specials: String,
        hud: HudObservation,
        solo: SoloSwap,
    ): Candidate? =
        solo.moves(board)
            .map { soloCandidate(board, it, specials, hud, solo) }
            // This comparator sorts the best ply first, so the winner is its minimum.
            .minWithOrNull(compareByDescending<Candidate> { it.plyScore }.then(ORDER))

    private fun soloCandidate(
        board: Board,
        move: Move,
        specials: String,
        hud: HudObservation,
        solo: SoloSwap,
    ): Candidate {
        val resolved = solo.resolve(board, move)
        val gems = gemScore(resolved.counts, hud.legendPoints)
        val ply = plyPoints(
            extraMove = resolved.extraMove,
            blue = resolved.blue,
            total = resolved.total,
            lowerRow = maxOf(move.r1, move.r2),
            uncertain = resolved.uncertain,
            blueFactor = hud.blueFactor,
            gemScore = gems,
            blueMultiplier = hud.blueMultiplier(),
        )
        return Candidate(
            move = move,
            matchLen = resolved.matchLen,
            extraMove = resolved.extraMove,
            blueCleared = resolved.blue,
            redCleared = resolved.red,
            totalCleared = resolved.total,
            cascadeSteps = resolved.steps,
            uncertain = resolved.uncertain,
            specials = specials,
            specialSpawn = resolved.specialSpawn,
            blueFactor = hud.blueFactor,
            lowerRow = maxOf(move.r1, move.r2),
            plyScore = ply,
            totalScore = ply.toDouble(),
        )
    }

    private fun score(board: Board, move: Move, specials: String): Candidate {
        val resolved = resolve(board, move)
        val ply = plyPoints(
            extraMove = resolved.extraMove,
            blue = resolved.blue,
            total = resolved.total,
            lowerRow = maxOf(move.r1, move.r2),
            uncertain = resolved.uncertain,
        )
        return Candidate(
            move = move,
            matchLen = resolved.matchLen,
            extraMove = resolved.extraMove,
            blueCleared = resolved.blue,
            totalCleared = resolved.total,
            cascadeSteps = resolved.steps,
            uncertain = resolved.uncertain,
            specials = specials,
            lowerRow = maxOf(move.r1, move.r2),
            plyScore = ply,
            totalScore = ply.toDouble(),
        )
    }

    private data class Resolved(
        val board: Board,
        val matchLen: Int,
        val extraMove: Boolean,
        val blue: Int,
        val total: Int,
        val steps: Int,
        val uncertain: Boolean,
    )

    /** Clear and drop known gems. Holes stay unknown and cannot match. */
    private fun resolve(board: Board, move: Move): Resolved {
        val swapped = board.swapCopy(move.r1, move.c1, move.r2, move.c2)
        val initial = detector.findMatches(swapped)
        val (matchLen, extraMove) = connectedClear(initial)
        var current = swapped
        var blue = 0
        var total = 0
        var steps = 0
        var uncertain = swapped.unknownCount() > 0
        while (steps < MAX_STEPS) {
            val matches = detector.findMatches(current)
            if (matches.isEmpty()) break
            val cells = matches.flatMap { it.cells }.toSet()
            if (adjacentUnknown(current, cells)) uncertain = true
            for ((row, col) in cells) {
                if (current.get(row, col).color == TileColor.B) blue++
                total++
            }
            current = gravity.apply(gravity.clearCells(current, cells)).board
            steps++
        }
        return Resolved(current, matchLen, extraMove, blue, total, steps, uncertain)
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
        /**
         * Extras first, and among extras the higher one (smaller row) first,
         * because a lower swap shifts the pieces above it. Other ties keep
         * blue, then total, then the lower row.
         */
        private val ORDER = Comparator<Candidate> { a, b ->
            if (a.extraMove != b.extraMove) {
                if (a.extraMove) -1 else 1
            } else if (a.extraMove && a.lowerRow != b.lowerRow) {
                a.lowerRow.compareTo(b.lowerRow)
            } else {
                val blue = b.blueCleared.compareTo(a.blueCleared)
                if (blue != 0) {
                    blue
                } else {
                    val total = b.totalCleared.compareTo(a.totalCleared)
                    if (total != 0) {
                        total
                    } else if (!a.extraMove && a.lowerRow != b.lowerRow) {
                        b.lowerRow.compareTo(a.lowerRow)
                    } else {
                        a.move.toString().compareTo(b.move.toString())
                    }
                }
            }
        }

        const val EXTRA_MOVE_POINTS = 1_000_000
        const val BLUE_POINTS = 1_000
        /**
         * Official Froggy Fu defaults, used when that color's legend digit was not read.
         * Blue 1, red 1, green 2, orange 3, purple 4, yellow 5.
         */
        val DEFAULT_LEGEND: Map<TileColor, Int> = mapOf(
            TileColor.B to 1,
            TileColor.R to 1,
            TileColor.G to 2,
            TileColor.O to 3,
            TileColor.P to 4,
            TileColor.Y to 5,
        )

        /** Scale of one unread gem before the legend table replaced it. */
        const val GEM_POINTS = 10
        const val ROW_POINTS = 1
        const val UNCERTAIN_PENALTY = 500
        const val FULL_BAR_BLUE_FACTOR = 0.15
        const val LEGEND_SCALE = 100
        const val DECISION_BUDGET_MS = 150L
        const val LOOKAHEAD_WIDTH = 15
        const val FOLLOW_WEIGHT_OURS = 1.0
        const val FOLLOW_WEIGHT_LIKELY = 0.7

        const val SCORE_FORMULA =
            "scoreFormula=solo ply = extra*1000000 + blue*1000*blueFactor + gemScore + lowerRow - uncertain*500; " +
                "gemScore = legendWeight*100 per gem when that color was read, otherwise the official " +
                "default (blue 1, red 1, green 2, orange 3, purple 4, yellow 5), logged as legend default; " +
                "among extra moves the higher one is played first; " +
                "blueFactor=0.15 when ACTIVATE or FULL is visible, else 1; " +
                "when a multiplier xN was read, the blue term is multiplied by N; " +
                "chosen = ply(now) + weight * ply(followUp); weight=1 when this ply is an extra move or the " +
                "move counter shows more than 1 left, weight=0 on a detected last move, otherwise 0.7; " +
                "the weight scales the follow-up only; an extra move is always 1000000 inside a ply; " +
                "4-line leaves an arrow, 5-line a color bomb, 5-L/T a bomb; unknown refills are never matches."

        const val MODE_GATE =
            "modeGate=solo lookahead runs on the solo layout. PvP lookahead runs on Your Turn or Time Left. " +
                "The session stops only on a positively read Opponent's Turn. An unrecognized HUD is " +
                "hudState=UNKNOWN and play continues. " +
                "ACTIVATE stays on the solo layout only, and only when the debug toggle is on."

        private val LOOKAHEAD_ORDER = compareByDescending<Candidate> { it.totalScore }
            .then(ORDER)

        fun followWeight(extraMove: Boolean, movesRemaining: Int?): Double = when {
            extraMove -> FOLLOW_WEIGHT_OURS
            movesRemaining == null -> FOLLOW_WEIGHT_LIKELY
            movesRemaining > 1 -> FOLLOW_WEIGHT_OURS
            else -> 0.0
        }

        fun gemScore(counts: Map<TileColor, Int>, weights: Map<TileColor, Int>): Int {
            var score = 0
            for ((color, count) in counts) {
                if (count == 0 || color == TileColor.UNKNOWN) continue
                val weight = weights[color] ?: DEFAULT_LEGEND[color] ?: 1
                score += count * weight * LEGEND_SCALE
            }
            return score
        }

        fun plyPoints(
            extraMove: Boolean,
            blue: Int,
            total: Int,
            lowerRow: Int,
            uncertain: Boolean,
            blueFactor: Double = 1.0,
            gemScore: Int = total * GEM_POINTS,
            blueMultiplier: Int = 1,
        ): Int {
            val scale = blueMultiplier.coerceAtLeast(1)
            val blueTerm = (blue * BLUE_POINTS * blueFactor * scale).roundToInt()
            val penalty = if (uncertain) UNCERTAIN_PENALTY else 0
            val extra = if (extraMove) EXTRA_MOVE_POINTS else 0
            return extra + blueTerm + gemScore + lowerRow * ROW_POINTS - penalty
        }

        val EMPTY_RANKING = Ranking(emptyList(), "none")
    }
}
