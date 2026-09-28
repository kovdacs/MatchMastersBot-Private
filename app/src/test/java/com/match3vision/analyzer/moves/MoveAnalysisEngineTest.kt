package com.match3vision.analyzer.moves

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.board.GameState
import com.match3vision.analyzer.board.VisionState
import com.match3vision.analyzer.evaluation.MoveEvaluator
import com.match3vision.analyzer.rules.MatchDetector
import com.match3vision.analyzer.vision.TileColor
import com.match3vision.analyzer.vision.VisionThresholds
import org.junit.Test

/**
 * MOVE ANALYSIS ENGINE V1 unit coverage.
 * Boards are constructed so expected match sizes are deterministic — no fake
 * hardcoded best-move for real frames.
 */
class MoveAnalysisEngineTest {

    private val engine = MoveAnalysisEngine()
    private val evaluator = MoveEvaluator()
    private val simulator = MoveSimulator()

    /** Fill 7×7 with a safe non-matching palette, then overlay puzzle cells. */
    private fun baseColors(): Array<Array<TileColor>> {
        val palette = listOf(
            TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O, TileColor.R,
        )
        return Array(7) { r ->
            Array(7) { c ->
                // Offset pattern avoids accidental 3-runs in unused cells.
                palette[(r * 3 + c * 2) % 6]
            }
        }
    }

    /** Classic: (0,0)=R (0,1)=R (0,2)=B (0,3)=R and (1,2)=R → swap (0,2)↔(1,2) = RRRR? No RRR of 3. */
    private fun board3Match(): Pair<Board, Move> {
        val colors = baseColors()
        // Row0: R R B R … ; bring R up from (1,2)
        colors[0][0] = TileColor.R
        colors[0][1] = TileColor.R
        colors[0][2] = TileColor.B
        colors[0][3] = TileColor.Y
        colors[1][0] = TileColor.Y
        colors[1][1] = TileColor.G
        colors[1][2] = TileColor.R
        colors[1][3] = TileColor.O
        colors[2][2] = TileColor.Y
        // Ensure no accidental longer run
        colors[0][4] = TileColor.G
        return Board.fromColors(colors) to Move(0, 2, 1, 2)
    }

    private fun board4Match(): Pair<Board, Move> {
        val colors = baseColors()
        // After swap (0,3)↔(1,3): row0 becomes R R R R
        colors[0][0] = TileColor.R
        colors[0][1] = TileColor.R
        colors[0][2] = TileColor.R
        colors[0][3] = TileColor.B
        colors[0][4] = TileColor.Y
        colors[1][0] = TileColor.Y
        colors[1][1] = TileColor.G
        colors[1][2] = TileColor.O
        colors[1][3] = TileColor.R
        colors[1][4] = TileColor.P
        colors[2][3] = TileColor.Y
        return Board.fromColors(colors) to Move(0, 3, 1, 3)
    }

    private fun board5Match(): Pair<Board, Move> {
        val colors = baseColors()
        // After swap (0,4)↔(1,4): row0 R R R R R
        colors[0][0] = TileColor.R
        colors[0][1] = TileColor.R
        colors[0][2] = TileColor.R
        colors[0][3] = TileColor.R
        colors[0][4] = TileColor.B
        colors[0][5] = TileColor.Y
        colors[1][0] = TileColor.Y
        colors[1][1] = TileColor.G
        colors[1][2] = TileColor.O
        colors[1][3] = TileColor.P
        colors[1][4] = TileColor.R
        colors[1][5] = TileColor.G
        colors[2][4] = TileColor.Y
        return Board.fromColors(colors) to Move(0, 4, 1, 4)
    }

    /** Two disjoint 3-matches from one swap (horizontal + vertical). */
    private fun boardMultiSimultaneous(): Pair<Board, Move> {
        val colors = baseColors()
        // Horizontal: after swap B at (2,2) with R at (2,3):
        // row2: R R R …
        colors[2][0] = TileColor.R
        colors[2][1] = TileColor.R
        colors[2][2] = TileColor.B
        colors[2][3] = TileColor.R
        colors[2][4] = TileColor.Y
        // Vertical: column 2 after same swap puts R at (2,2) completing (0,2)(1,2)(2,2)
        colors[0][2] = TileColor.R
        colors[1][2] = TileColor.R
        colors[3][2] = TileColor.Y
        colors[0][3] = TileColor.Y
        colors[1][3] = TileColor.G
        colors[3][3] = TileColor.O
        return Board.fromColors(colors) to Move(2, 2, 2, 3)
    }

    private fun boardNoMoves(): Board {
        // Latin (r+c)%6 over 6 colors — zero legal adjacent swaps (verified).
        val palette = listOf(
            TileColor.R, TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O,
        )
        val colors = Array(7) { r ->
            Array(7) { c -> palette[(r + c) % 6] }
        }
        return Board.fromColors(colors)
    }

    private fun passState(board: Board) = GameState(
        board = board,
        vision = VisionState(
            gatePass = true,
            boardConfidence = VisionThresholds.MIN_BOARD_CONFIDENCE,
            gridConfidence = VisionThresholds.MIN_GRID_CONFIDENCE,
            unknownCount = 0,
        ),
    )

    @Test
    fun simple_3_match_clearsAtLeastThree() {
        val (board, move) = board3Match()
        val swapped = board.swapCopy(move.r1, move.c1, move.r2, move.c2)
        val matches = MatchDetector().findMatches(swapped)
        assertThat(matches.any { it.size == 3 }).isTrue()
        val sim = simulator.simulate(board, move)
        assertThat(sim.legal).isTrue()
        assertThat(sim.clearedCells).isAtLeast(3)
        assertThat(sim.maxMatchSize).isAtLeast(3)
        val eval = evaluator.evaluate(board, move)
        assertThat(eval.matchScore).isGreaterThan(0f)
        assertThat(eval.WHY).isNotEmpty()
        assertThat(eval.EV).isFinite()
    }

    @Test
    fun four_match_scoresHigherSizeBonus_andMayCreateSpecial() {
        val (board, move) = board4Match()
        val swapped = board.swapCopy(move.r1, move.c1, move.r2, move.c2)
        assertThat(MatchDetector().findMatches(swapped).any { it.size >= 4 }).isTrue()
        val sim = simulator.simulate(board, move)
        assertThat(sim.legal).isTrue()
        assertThat(sim.maxMatchSize).isAtLeast(4)
        assertThat(sim.specialsCreated).isAtLeast(1)
        val eval = evaluator.evaluate(board, move)
        assertThat(eval.specialCreated || eval.special > 0f || eval.extraMove > 0f).isTrue()
        assertThat(eval.maxMatchSize).isAtLeast(4)
    }

    @Test
    fun five_match_createsLightningProxy_andExtraMove() {
        val (board, move) = board5Match()
        val swapped = board.swapCopy(move.r1, move.c1, move.r2, move.c2)
        assertThat(MatchDetector().findMatches(swapped).any { it.size >= 5 }).isTrue()
        val sim = simulator.simulate(board, move)
        assertThat(sim.legal).isTrue()
        assertThat(sim.maxMatchSize).isAtLeast(5)
        assertThat(sim.specialsCreated).isAtLeast(1)
        assertThat(sim.extraMoveLikely).isTrue()
        val eval = evaluator.evaluate(board, move)
        assertThat(eval.extraMove).isGreaterThan(0f)
        assertThat(eval.specialCreated).isTrue()
        assertThat(sim.specialsCreated).isAtLeast(1)
    }

    @Test
    fun multi_simultaneous_matches_detected() {
        val (board, move) = boardMultiSimultaneous()
        val swapped = board.swapCopy(move.r1, move.c1, move.r2, move.c2)
        val groups = MatchDetector().findMatches(swapped)
        assertThat(groups.size).isAtLeast(2)
        val sim = simulator.simulate(board, move)
        assertThat(sim.legal).isTrue()
        assertThat(sim.concurrentMatches).isAtLeast(2)
        val eval = evaluator.evaluate(board, move)
        assertThat(eval.concurrentMatches).isAtLeast(2)
        assertThat(eval.matchScore).isGreaterThan(30f) // size/concurrent bonuses
    }

    @Test
    fun special_create_from_length4() {
        val (board, move) = board4Match()
        val sim = simulator.simulate(board, move)
        assertThat(sim.specialsCreated).isAtLeast(1)
        assertThat(sim.maxMatchSize).isAtLeast(4)
        // Special may be consumed by a later cascade step; creation count is the contract.
        val eval = evaluator.evaluate(board, move)
        assertThat(eval.specialCreated).isTrue()
        assertThat(eval.special).isGreaterThan(0f)
        assertThat(eval.extraMove).isGreaterThan(0f)
    }

    @Test
    fun no_valid_move_returnsEmptyTop5() {
        val board = boardNoMoves()
        assertThat(LegalMoveGenerator().generate(board)).isEmpty()
        val result = engine.analyze(passState(board))
        assertThat(result.blocked).isFalse()
        assertThat(result.top5).isEmpty()
        assertThat(result.hasMoves).isFalse()
    }

    @Test
    fun unknown_or_hold_gate_blocks_with_no_moves() {
        val (board, _) = board3Match()
        val hold = engine.analyze(
            GameState(
                board = board,
                vision = VisionState(
                    gatePass = false,
                    holdReason = "HOLD — Decision AI blocked",
                    boardConfidence = 0.5f,
                    gridConfidence = 0.5f,
                    unknownCount = 5,
                ),
            ),
        )
        assertThat(hold.blocked).isTrue()
        assertThat(hold.topMoves).isEmpty()
        assertThat(hold.holdReason).contains("Decision AI blocked")

        val lowGrid = engine.analyzeBoard(
            board = board,
            gatePass = true,
            gridConfidence = 0.90f,
            boardConfidence = 0.99f,
            unknownCount = 0,
        )
        assertThat(lowGrid.blocked).isTrue()
        assertThat(lowGrid.topMoves).isEmpty()

        val lowBoard = engine.analyzeBoard(
            board = board,
            gatePass = true,
            gridConfidence = 0.99f,
            boardConfidence = 0.90f,
            unknownCount = 0,
        )
        assertThat(lowBoard.blocked).isTrue()
        assertThat(lowBoard.topMoves).isEmpty()

        val tooManyUnknown = engine.analyzeBoard(
            board = board,
            gatePass = true,
            gridConfidence = 0.99f,
            boardConfidence = 0.99f,
            unknownCount = 2,
        )
        assertThat(tooManyUnknown.blocked).isTrue()
        assertThat(tooManyUnknown.topMoves).isEmpty()
    }

    @Test
    fun top5_ordering_by_ev_descending() {
        val (board, _) = board3Match()
        // Enrich board so several legal moves exist (reuse 3-match + 4-match overlay region)
        val colors = baseColors()
        colors[0][0] = TileColor.R
        colors[0][1] = TileColor.R
        colors[0][2] = TileColor.B
        colors[0][3] = TileColor.Y
        colors[1][2] = TileColor.R
        colors[3][0] = TileColor.G
        colors[3][1] = TileColor.G
        colors[3][2] = TileColor.B
        colors[4][2] = TileColor.G
        colors[5][4] = TileColor.P
        colors[5][5] = TileColor.P
        colors[5][6] = TileColor.B
        colors[6][6] = TileColor.P
        val rich = Board.fromColors(colors)
        val result = engine.analyze(passState(rich))
        assertThat(result.blocked).isFalse()
        assertThat(result.top5).isNotEmpty()
        assertThat(result.top5.size).isAtMost(5)
        for (i in 0 until result.top5.lastIndex) {
            assertThat(result.top5[i].EV).isAtLeast(result.top5[i + 1].EV)
            assertThat(result.top5[i].score).isAtLeast(result.top5[i + 1].score)
        }
        val top = result.top5.first()
        assertThat(top.confidence).isGreaterThan(0f)
        assertThat(top.WHY).isNotEmpty()
        assertThat(top.risk).isAtLeast(0f)
    }

    @Test
    fun pass_gate_exposes_v1_fields() {
        val (board, move) = board3Match()
        val result = engine.analyze(passState(board))
        assertThat(result.blocked).isFalse()
        assertThat(result.top5).isNotEmpty()
        val hit = result.top5.first { it.move.normalized() == move.normalized() }
        assertThat(hit.stars).isAtLeast(0f)
        assertThat(hit.booster).isAtLeast(0f)
        assertThat(hit.cascade).isAtLeast(0f)
        assertThat(hit.future).isFinite()
        assertThat(hit.opponent).isFinite()
        assertThat(hit.extraMove).isAtLeast(0f)
        assertThat(hit.EV).isEqualTo(hit.expectedValue)
        assertThat(hit.score).isEqualTo(hit.totalScore)
    }
}
