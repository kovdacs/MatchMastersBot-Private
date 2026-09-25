package com.match3vision.analyzer.rules

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.vision.SpecialType
import com.match3vision.analyzer.vision.TileColor
import org.junit.Test

class RuleEngineTest {
    private fun boardWithMatch(): Board {
        val colors = Array(7) { r -> Array(7) { c ->
            listOf(TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O, TileColor.R)[(r * 2 + c) % 6]
        } }
        colors[3][0] = TileColor.R; colors[3][1] = TileColor.R; colors[3][2] = TileColor.R
        colors[2][0] = TileColor.Y; colors[4][0] = TileColor.Y
        colors[2][1] = TileColor.G; colors[4][1] = TileColor.G
        colors[2][2] = TileColor.B; colors[4][2] = TileColor.B
        return Board.fromColors(colors)
    }

    @Test fun matchDetector_findsHorizontal() {
        assertThat(MatchDetector().findMatches(boardWithMatch()).any { it.horizontal && it.size >= 3 }).isTrue()
    }

    @Test fun swapValidator_rejectsUnknown() {
        var b = boardWithMatch()
        b = b.setCopy(0, 0, b.get(0, 0).copy(color = TileColor.UNKNOWN))
        val v = SwapValidator().validate(b, 0, 0, 0, 1)
        assertThat(v.legal).isFalse()
        assertThat(v.uncertain).isTrue()
    }

    @Test fun specialCreator_len4_and_5() {
        val g4 = MatchDetector.MatchGroup(listOf(0 to 0, 0 to 1, 0 to 2, 0 to 3), TileColor.B, true)
        val g5 = MatchDetector.MatchGroup((0..4).map { 1 to it }, TileColor.R, true)
        assertThat(SpecialCreator().fromMatchGroups(listOf(g4)).single().special).isEqualTo(SpecialType.TWO_WAY_ARROW)
        assertThat(SpecialCreator().fromMatchGroups(listOf(g5)).single().special).isEqualTo(SpecialType.LIGHTNING)
    }

    @Test fun gravity_and_cascade() {
        val board = boardWithMatch()
        val cleared = GravityEngine().clearCells(board, setOf(6 to 0))
        assertThat(GravityEngine().apply(cleared).board.get(0, 0).visible).isFalse()
        assertThat(CascadeEngine(doRefill = false).run(board).clearedCells).isAtLeast(3)
    }

    @Test fun refill_unknownWithoutProvider() {
        val board = GravityEngine().clearCells(boardWithMatch(), setOf(0 to 0))
        val rr = BoardRefill().refill(board)
        assertThat(rr.uncertain).isTrue()
    }

    @Test fun combo_lightning() {
        val r = SpecialCombinationResolver().resolve(SpecialType.LIGHTNING, SpecialType.BOMB)
        assertThat(r.uncertain).isFalse()
    }
}
