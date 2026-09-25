package com.match3vision.analyzer.moves

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.vision.TileColor
import org.junit.Test

class MoveEngineTest {
    private fun puzzle(): Board {
        val colors = Array(7) { r -> Array(7) { c ->
            listOf(TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O, TileColor.R)[(r * 2 + c) % 6]
        } }
        colors[0][0] = TileColor.R; colors[0][1] = TileColor.R; colors[0][2] = TileColor.B; colors[0][3] = TileColor.R
        colors[1][2] = TileColor.R; colors[2][2] = TileColor.Y; colors[3][2] = TileColor.G
        return Board.fromColors(colors)
    }

    @Test fun generator_reproducible_and_findsMove() {
        val g = LegalMoveGenerator()
        val a = g.generate(puzzle())
        assertThat(a).isEqualTo(g.generate(puzzle()))
        assertThat(a).isNotEmpty()
    }

    @Test fun generator_excludesUnknown() {
        var b = puzzle()
        b = b.setCopy(0, 2, b.get(0, 2).copy(color = TileColor.UNKNOWN))
        assertThat(LegalMoveGenerator().generate(b).none {
            (it.r1 == 0 && it.c1 == 2) || (it.r2 == 0 && it.c2 == 2)
        }).isTrue()
    }

    @Test fun simulator_clears() {
        val sim = MoveSimulator().simulate(puzzle(), Move(0, 2, 1, 2))
        assertThat(sim.legal).isTrue()
        assertThat(sim.clearedCells).isAtLeast(3)
    }

    @Test fun future_and_validator() {
        val b = puzzle()
        val m = Move(0, 2, 1, 2)
        assertThat(FutureBoardGenerator().afterMove(b, m).contentHash()).isNotEqualTo(b.contentHash())
        assertThat(MoveValidator().isLegal(b, m)).isTrue()
    }
}
