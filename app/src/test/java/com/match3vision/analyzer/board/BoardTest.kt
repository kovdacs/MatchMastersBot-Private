package com.match3vision.analyzer.board

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.vision.TileColor
import org.junit.Test

class BoardTest {
    private fun colors(): Array<Array<TileColor>> = Array(7) { r ->
        Array(7) { c ->
            listOf(TileColor.B, TileColor.R, TileColor.Y, TileColor.G, TileColor.P, TileColor.O)[(r + c) % 6]
        }
    }

    @Test fun fromColors_valid() {
        val b = Board.fromColors(colors())
        assertThat(b.isValidStructure()).isTrue()
        assertThat(b.unknownCount()).isEqualTo(0)
    }

    @Test fun swapCopy_immutable() {
        val b = Board.fromColors(colors())
        val a = b.get(0, 0).color
        val s = b.swapCopy(0, 0, 0, 1)
        assertThat(b.get(0, 0).color).isEqualTo(a)
        assertThat(s.get(0, 0).color).isEqualTo(b.get(0, 1).color)
    }

    @Test fun contentHash_changes() {
        val b = Board.fromColors(colors())
        val c = b.setCopy(0, 0, Tile.unknown(0, 0))
        assertThat(c.contentHash()).isNotEqualTo(b.contentHash())
    }

    @Test fun history_duplicate() {
        val h = BoardHistory()
        val b = Board.fromColors(colors())
        h.push(b, 1)
        assertThat(h.isDuplicateOfLast(b)).isTrue()
    }

    @Test fun gameState_defaultsUnknownHud() {
        val gs = GameState(board = Board.fromColors(colors()))
        assertThat(gs.player.observable).isFalse()
        assertThat(gs.opponent.observable).isFalse()
    }
}
