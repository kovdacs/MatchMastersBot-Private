package com.match3vision.analyzer.moves

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.vision.SpecialType
import com.match3vision.analyzer.vision.TileColor
import com.match3vision.analyzer.vision.VisionThresholds
import org.junit.Test

/**
 * Hand-built boards for the owner-confirmed extra-move rule and the play order.
 */
class PlayMoveRankerTest {
    private val ranker = PlayMoveRanker()

    private fun latin(): Array<Array<TileColor>> {
        val palette = listOf(
            TileColor.R, TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O,
        )
        return Array(7) { row -> Array(7) { col -> palette[(row + col) % 6] } }
    }

    private fun board(paint: Array<Array<TileColor>>.() -> Unit): Board {
        val colors = latin()
        colors.paint()
        return Board.fromColors(colors)
    }

    private fun find(board: Board, move: Move): PlayMoveRanker.Candidate {
        val hit = ranker.rank(board).ordered.firstOrNull { it.move.normalized() == move.normalized() }
        assertThat(hit).isNotNull()
        return hit!!
    }

    @Test
    fun straightFour_isExtraMove_andBeatsBlueThree() {
        val board = board {
            // Straight red 4: swap (0,3)↔(1,3).
            this[0][0] = TileColor.R
            this[0][1] = TileColor.R
            this[0][2] = TileColor.R
            this[0][3] = TileColor.B
            this[0][4] = TileColor.Y
            this[1][3] = TileColor.R
            this[2][3] = TileColor.Y
            // Blue 3 lower on the board: swap (5,2)↔(6,2).
            this[5][0] = TileColor.B
            this[5][1] = TileColor.B
            this[5][2] = TileColor.R
            this[5][3] = TileColor.Y
            this[6][2] = TileColor.B
            this[4][2] = TileColor.G
        }
        val four = find(board, Move(0, 3, 1, 3))
        val blue = find(board, Move(5, 2, 6, 2))
        assertThat(four.extraMove).isTrue()
        assertThat(four.matchLen).isAtLeast(4)
        assertThat(blue.extraMove).isFalse()
        assertThat(blue.blueCleared).isAtLeast(3)
        val ordered = ranker.rank(board).ordered
        assertThat(ordered.indexOf(four)).isLessThan(ordered.indexOf(blue))
    }

    @Test
    fun straightFive_isExtraMove() {
        val board = board {
            this[0][0] = TileColor.R
            this[0][1] = TileColor.R
            this[0][2] = TileColor.R
            this[0][3] = TileColor.R
            this[0][4] = TileColor.B
            this[0][5] = TileColor.Y
            this[1][4] = TileColor.R
            this[2][4] = TileColor.G
        }
        val five = find(board, Move(0, 4, 1, 4))
        assertThat(five.extraMove).isTrue()
        assertThat(five.matchLen).isAtLeast(5)
    }

    @Test
    fun plusShape_ofThreePlusThree_isExtraMove() {
        val board = board {
            this[0][2] = TileColor.G
            this[1][2] = TileColor.R
            this[2][0] = TileColor.B
            this[2][1] = TileColor.R
            this[2][2] = TileColor.Y
            this[2][3] = TileColor.R
            this[2][4] = TileColor.P
            this[3][2] = TileColor.R
            this[4][2] = TileColor.R
            this[5][2] = TileColor.P
        }
        val plus = find(board, Move(1, 2, 2, 2))
        assertThat(plus.extraMove).isTrue()
        assertThat(plus.matchLen).isAtLeast(5)
    }

    @Test
    fun ellShape_isExtraMove_evenThoughEachLineIsThree() {
        val board = board {
            this[2][0] = TileColor.R
            this[2][1] = TileColor.R
            this[2][2] = TileColor.B
            this[2][3] = TileColor.Y
            this[1][2] = TileColor.R
            this[3][2] = TileColor.R
            this[4][2] = TileColor.R
            this[0][2] = TileColor.G
            this[5][2] = TileColor.P
        }
        val ell = find(board, Move(1, 2, 2, 2))
        assertThat(ell.extraMove).isTrue()
        assertThat(ell.matchLen).isAtLeast(5)
    }

    @Test
    fun twoColorsOfThree_areNotAnExtraMove() {
        val board = board {
            // One swap completes a red 3 and a blue 3. Neither color reaches 4.
            this[2][0] = TileColor.R
            this[2][1] = TileColor.R
            this[2][2] = TileColor.B
            this[2][3] = TileColor.R
            this[2][4] = TileColor.Y
            this[1][2] = TileColor.G
            this[3][2] = TileColor.G
            this[0][3] = TileColor.B
            this[1][3] = TileColor.B
            this[3][3] = TileColor.P
        }
        val both = find(board, Move(2, 2, 2, 3))
        assertThat(both.extraMove).isFalse()
        assertThat(both.matchLen).isLessThan(4)
        assertThat(both.totalCleared).isAtLeast(6)
    }

    @Test
    fun blueThree_beatsRedThree_whenNeitherIsExtra() {
        val board = board {
            this[1][0] = TileColor.R
            this[1][1] = TileColor.R
            this[1][2] = TileColor.Y
            this[0][2] = TileColor.R
            this[2][2] = TileColor.G
            this[5][4] = TileColor.B
            this[5][5] = TileColor.B
            this[5][6] = TileColor.R
            this[6][6] = TileColor.B
            this[4][6] = TileColor.G
        }
        val red = find(board, Move(0, 2, 1, 2))
        val blue = find(board, Move(5, 6, 6, 6))
        assertThat(red.extraMove).isFalse()
        assertThat(blue.extraMove).isFalse()
        assertThat(blue.blueCleared).isGreaterThan(red.blueCleared)
        val ordered = ranker.rank(board).ordered
        assertThat(ordered.indexOf(blue)).isLessThan(ordered.indexOf(red))
    }

    @Test
    fun twoExtras_playsTheHigherOneFirst() {
        val board = board {
            this[0][0] = TileColor.R
            this[0][1] = TileColor.R
            this[0][2] = TileColor.R
            this[0][3] = TileColor.G
            this[1][3] = TileColor.R
            this[2][3] = TileColor.Y
            this[6][0] = TileColor.B
            this[6][1] = TileColor.B
            this[6][2] = TileColor.B
            this[6][3] = TileColor.R
            this[5][3] = TileColor.B
            this[4][3] = TileColor.Y
        }
        val higher = find(board, Move(0, 3, 1, 3))
        val lower = find(board, Move(5, 3, 6, 3))
        assertThat(higher.extraMove).isTrue()
        assertThat(lower.extraMove).isTrue()
        assertThat(lower.blueCleared).isGreaterThan(higher.blueCleared)
        val ordered = ranker.rank(board).ordered
        assertThat(ordered.indexOf(higher)).isLessThan(ordered.indexOf(lower))
    }

    @Test
    fun sameScore_prefersTheLowerRow() {
        val board = board {
            this[1][0] = TileColor.G
            this[1][1] = TileColor.G
            this[1][2] = TileColor.Y
            this[0][2] = TileColor.G
            this[2][2] = TileColor.R
            this[5][0] = TileColor.G
            this[5][1] = TileColor.G
            this[5][2] = TileColor.Y
            this[6][2] = TileColor.G
            this[4][2] = TileColor.R
        }
        val high = find(board, Move(0, 2, 1, 2))
        val low = find(board, Move(5, 2, 6, 2))
        assertThat(high.extraMove).isFalse()
        assertThat(low.extraMove).isFalse()
        assertThat(high.blueCleared).isEqualTo(low.blueCleared)
        assertThat(high.totalCleared).isEqualTo(low.totalCleared)
        val ordered = ranker.rank(board).ordered
        assertThat(ordered.indexOf(low)).isLessThan(ordered.indexOf(high))
    }

    @Test
    fun cascade_countsBlueThatFallsIntoAMatch_andDoesNotInventRefill() {
        val board = board {
            // Swap (4,2)↔(4,3) makes a red 3 that clears the hole at (4,1).
            // Two blues above that hole fall onto the blue under it.
            this[4][0] = TileColor.R
            this[4][1] = TileColor.R
            this[4][2] = TileColor.Y
            this[4][3] = TileColor.R
            this[4][4] = TileColor.P
            this[0][1] = TileColor.P
            this[1][1] = TileColor.P
            this[2][1] = TileColor.B
            this[3][1] = TileColor.B
            this[5][1] = TileColor.B
            this[6][1] = TileColor.P
            this[3][0] = TileColor.O
            this[5][0] = TileColor.G
            this[3][2] = TileColor.O
            this[5][2] = TileColor.G
            this[3][3] = TileColor.O
            this[5][3] = TileColor.G
        }
        val cascade = find(board, Move(4, 2, 4, 3))
        assertThat(cascade.cascadeSteps).isAtLeast(2)
        // Three known blues fall into a line. Holes are not counted as blue gems.
        assertThat(cascade.blueCleared).isEqualTo(3)
        assertThat(cascade.totalCleared).isAtLeast(6)
    }

    @Test
    fun unknownNeighbor_doesNotCountAsABlueMatch() {
        val colors = latin()
        colors[6][0] = TileColor.R
        colors[6][1] = TileColor.R
        colors[6][2] = TileColor.Y
        colors[5][2] = TileColor.R
        colors[6][3] = TileColor.UNKNOWN
        colors[5][3] = TileColor.B
        colors[4][2] = TileColor.G
        val board = Board.fromColors(colors)
        val move = find(board, Move(5, 2, 6, 2))
        assertThat(move.blueCleared).isEqualTo(0)
        assertThat(move.totalCleared).isEqualTo(3)
        assertThat(move.uncertain).isTrue()
    }

    @Test
    fun noLegalMove_exportsNone_andNamesTheOwnerRule() {
        val ranking = ranker.rank(Board.fromColors(latin()))
        assertThat(ranking.ordered).isEmpty()
        assertThat(ranking.export()).contains("playTop3: none")
        assertThat(ranking.export()).contains("confirmed by the owner")
        assertThat(ranking.export()).contains("any shape")
    }

    @Test
    fun specialSwap_isNotSelected_butTheSpecialIsReported() {
        var board = board {
            this[0][0] = TileColor.R
            this[0][1] = TileColor.R
            this[0][2] = TileColor.R
            this[0][3] = TileColor.B
            this[1][3] = TileColor.R
            this[5][0] = TileColor.G
            this[5][1] = TileColor.G
            this[5][2] = TileColor.Y
            this[6][2] = TileColor.G
        }
        val bomb = board.get(0, 3).copy(special = SpecialType.BOMB)
        board = board.setCopy(0, 3, bomb)
        val ranking = ranker.rank(board)
        assertThat(ranking.boardSpecials).contains("BOMB")
        assertThat(ranking.ordered.map { it.move.normalized() })
            .doesNotContain(Move(0, 3, 1, 3).normalized())
        assertThat(ranking.ordered.map { it.move.normalized() })
            .contains(Move(5, 2, 6, 2).normalized())
        assertThat(ranking.ordered.first().toEvaluation().boosterScore).isEqualTo(0f)
    }

    @Test
    fun visionGates_stayAtThe02477Thresholds() {
        assertThat(VisionThresholds.MIN_GRID_CONFIDENCE).isEqualTo(0.98f)
        assertThat(VisionThresholds.MIN_BOARD_CONFIDENCE).isEqualTo(0.95f)
        assertThat(VisionThresholds.MAX_UNKNOWN_COUNT).isEqualTo(1)
    }
}
