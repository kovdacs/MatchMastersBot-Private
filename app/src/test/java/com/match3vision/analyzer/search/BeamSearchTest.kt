package com.match3vision.analyzer.search

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.vision.TileColor
import org.junit.Test

class BeamSearchTest {
    private fun puzzle(): Board {
        val colors = Array(7) { r -> Array(7) { c ->
            listOf(TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O, TileColor.R)[(r * 2 + c) % 6]
        } }
        colors[0][0] = TileColor.R; colors[0][1] = TileColor.R; colors[0][2] = TileColor.B; colors[0][3] = TileColor.R
        colors[1][2] = TileColor.R
        return Board.fromColors(colors)
    }

    @Test fun search_finite_respectsDepth() {
        val tree = BeamSearch(maxDepth = 2, beamWidth = 3, timeBudgetMs = 200, topN = 3).search(puzzle())
        assertThat(tree.topLeaves.size).isAtMost(3)
        assertThat(tree.nodesExpanded).isGreaterThan(0)
        tree.topLeaves.forEach { assertThat(it.depth).isAtMost(2) }
    }

    @Test fun search_timeBudget_returns() {
        val tree = BeamSearch(maxDepth = 5, beamWidth = 8, timeBudgetMs = 1, topN = 5).search(puzzle())
        assertThat(tree.nodesExpanded).isAtLeast(0)
    }
}
