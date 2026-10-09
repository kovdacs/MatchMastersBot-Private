package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.moves.PlayMoveRanker
import com.match3vision.analyzer.vision.TileColor
import org.junit.Test

/** Lex scoring and swap-axis helper kept after the 0.26.4 settle restore. */
class PlaySafety0265Test {
    @Test
    fun supports_acceptsACellOnTheSwappedRow_withoutRequiringBothAxes() {
        val before = latin()
        val after = latin()
        after[3][6] = TileColor.B
        val changed = AutoCalibration.changedCells(Board.fromColors(before), Board.fromColors(after))
        assertThat(AutoCalibration.supports(changed, 3, 2, 3, 3)).isTrue()
        assertThat(AutoCalibration.overlaps(changed, 3, 2, 3, 3)).isFalse()
        assertThat(AutoCalibration.supports(changed, 0, 0, 1, 1)).isFalse()
    }

    @Test
    fun followUpPenalty_isSmaller_andAMultiplierScalesGemsNotBlue() {
        val now = PlayMoveRanker.plyPoints(false, 0, 3, 0, true, followUp = false)
        val later = PlayMoveRanker.plyPoints(false, 0, 3, 0, true, followUp = true)
        assertThat(later - now).isEqualTo(
            PlayMoveRanker.UNCERTAIN_PENALTY - PlayMoveRanker.FOLLOW_UNCERTAIN_PENALTY,
        )
        val plain = PlayMoveRanker.plyPoints(false, 2, 2, 0, false, blueMultiplier = 1)
        val boosted = PlayMoveRanker.plyPoints(false, 2, 2, 0, false, blueMultiplier = 4)
        val blue = (2 * PlayMoveRanker.BLUE_POINTS)
        assertThat(plain - blue).isEqualTo(2 * PlayMoveRanker.GEM_POINTS)
        assertThat(boosted - blue).isEqualTo(2 * PlayMoveRanker.GEM_POINTS * 4)
        assertThat(PlayMoveRanker.SCORE_FORMULA).contains("+")
    }

    private fun latin(): Array<Array<TileColor>> {
        val palette = listOf(TileColor.R, TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O)
        return Array(7) { row -> Array(7) { col -> palette[(row + col) % 6] } }
    }
}
