package com.match3vision.analyzer.moves

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.hud.BoosterControl
import com.match3vision.analyzer.hud.BoosterMonitor
import com.match3vision.analyzer.hud.HudObservation
import com.match3vision.analyzer.hud.HudReader
import com.match3vision.analyzer.hud.SoloBooster
import com.match3vision.analyzer.vision.SpecialType
import com.match3vision.analyzer.vision.TileColor
import org.junit.Test

/**
 * Solo Perfect Heist lookahead. A HUD that is not the solo layout keeps the
 * 0.25.0 order and never plans an ACTIVATE tap.
 */
class LookaheadSoloTest {
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

    private fun trap(): Board = board {
        this[0][2] = TileColor.P
        this[1][2] = TileColor.O
        this[2][2] = TileColor.G
        this[3][2] = TileColor.R
        this[4][2] = TileColor.R
        this[5][2] = TileColor.Y
        this[6][2] = TileColor.R
        this[5][1] = TileColor.G
        this[5][3] = TileColor.P
        this[5][4] = TileColor.G
        this[4][3] = TileColor.G
        this[0][4] = TileColor.B
        this[1][4] = TileColor.Y
        this[2][1] = TileColor.B
        this[4][5] = TileColor.P
        this[6][3] = TileColor.O
        this[0][5] = TileColor.B
        this[0][6] = TileColor.Y
        this[1][6] = TileColor.B
        this[1][5] = TileColor.R
    }

    @Test
    fun formula_immediateExtraBeatsDiscountedSetup_whichBeatsBlue() {
        val extra = PlayMoveRanker.plyPoints(true, 0, 4, 0, false)
        val setup = (PlayMoveRanker.FOLLOW_WEIGHT_LIKELY * extra).toInt()
        val blue = PlayMoveRanker.plyPoints(false, 49, 49, 6, false)
        assertThat(extra).isGreaterThan(setup)
        assertThat(setup).isGreaterThan(blue)
        assertThat(PlayMoveRanker.followWeight(true, null)).isEqualTo(1.0)
        assertThat(PlayMoveRanker.followWeight(false, null)).isEqualTo(0.7)
        assertThat(PlayMoveRanker.followWeight(false, 4)).isEqualTo(1.0)
        assertThat(PlayMoveRanker.followWeight(false, 1)).isEqualTo(0.0)
    }

    @Test
    fun nonSoloHud_keepsTheGreedyOrder_andDoesNotTap() {
        val board = trap()
        val greedy = ranker.rank(board)
        val looked = ranker.rankLookahead(board, hud = HudObservation.UNKNOWN)
        assertThat(looked.lookahead).isEqualTo("skipped-mode")
        assertThat(looked.ordered.map { it.move.toString() })
            .isEqualTo(greedy.ordered.map { it.move.toString() })
        assertThat(looked.export()).contains("modeGate=")
        BoosterControl.enabled = false
        assertThat(SoloBooster.plan(HudObservation.solo(activateVisible = true), 1080, 2400, false)).isNull()
        assertThat(BoosterMonitor.mayTap(false, true, true)).isFalse()
    }

    @Test
    fun soloLookahead_prefersTheSetup_whenGreedyPrefersBlue() {
        val board = trap()
        val greedy = ranker.rank(board).ordered.first()
        val looked = ranker.rankLookahead(board, hud = HudObservation.solo())
        val chosen = looked.ordered.first()
        assertThat(looked.lookahead).isEqualTo("used")
        assertThat(chosen.move).isEqualTo(Move(5, 2, 6, 2))
        assertThat(greedy.extraMove).isFalse()
        assertThat(greedy.blueCleared).isGreaterThan(0)
        assertThat(greedy.move).isNotEqualTo(chosen.move)
        assertThat(chosen.followUpExtraMove).isTrue()
        assertThat(looked.export()).contains("differsFromGreedy=yes")
        assertThat(looked.decisionMs).isLessThan(PlayMoveRanker.DECISION_BUDGET_MS)
    }

    @Test
    fun equalMoves_lookaheadAgreesWithGreedy() {
        // Two certain 3-matches whose best follow-ups are worth the same.
        // Lookahead keeps the 0.25.0 order: the lower row.
        val board = board {
            this[2][0] = TileColor.R
            this[2][1] = TileColor.R
            this[1][2] = TileColor.R
            this[6][1] = TileColor.Y
        }
        val greedy = ranker.rank(board)
        val looked = ranker.rankLookahead(board, hud = HudObservation.solo())
        val moves = listOf(Move(5, 3, 6, 3), Move(1, 2, 2, 2))
        assertThat(greedy.ordered.map { it.move }).isEqualTo(moves)
        assertThat(looked.ordered.map { it.move }).isEqualTo(moves)
        val low = looked.ordered[0]
        val high = looked.ordered[1]
        assertThat(low.followUp).isNotNull()
        assertThat(high.followUp).isNotNull()
        assertThat(low.followWeight).isEqualTo(PlayMoveRanker.FOLLOW_WEIGHT_LIKELY)
        assertThat(low.totalScore - high.totalScore).isWithin(0.001).of(4.0)
        assertThat(looked.export()).contains("differsFromGreedy=no")
    }

    @Test
    fun predictionWrong_followUpExtraDisappears_andUnknownIsNotBlue() {
        val board = trap()
        val after = ranker.soloAfter(board, Move(5, 2, 6, 2))
        val follow = ranker.rankLookahead(after, hud = HudObservation.solo()).ordered.first()
        assertThat(follow.extraMove).isTrue()
        val broken = after.setCopy(
            follow.move.r1,
            follow.move.c1,
            after.get(follow.move.r1, follow.move.c1).copy(color = TileColor.Y),
        )
        val again = SoloSwap().resolve(broken, follow.move)
        assertThat(again.extraMove).isFalse()

        val colors = latin()
        colors[6][0] = TileColor.R
        colors[6][1] = TileColor.R
        colors[6][2] = TileColor.Y
        colors[5][2] = TileColor.R
        colors[6][3] = TileColor.UNKNOWN
        colors[5][3] = TileColor.B
        colors[4][2] = TileColor.G
        val unknown = SoloSwap().resolve(Board.fromColors(colors), Move(5, 2, 6, 2))
        assertThat(unknown.blue).isEqualTo(0)
        assertThat(unknown.total).isEqualTo(3)
        assertThat(unknown.uncertain).isTrue()
    }

    @Test
    fun budgetFallback_returnsTheGreedyOrder() {
        val board = trap()
        var ticks = 0
        val looked = ranker.rankLookahead(board, hud = HudObservation.solo(), clock = {
            ticks++
            if (ticks == 1) 0L else 200_000_000L
        })
        assertThat(looked.lookahead).isEqualTo("fallback-budget")
        assertThat(looked.ordered.map { it.move.toString() })
            .isEqualTo(ranker.rank(board).ordered.map { it.move.toString() })
    }

    @Test
    fun legendWeights_preferYellowOverGreen_andFullBarLowersBlue() {
        val weights = mapOf(TileColor.Y to 5, TileColor.G to 2)
        val yellow = PlayMoveRanker.gemScore(mapOf(TileColor.Y to 3), weights)
        val green = PlayMoveRanker.gemScore(mapOf(TileColor.G to 3), weights)
        assertThat(yellow).isGreaterThan(green)
        val flat = PlayMoveRanker.gemScore(mapOf(TileColor.Y to 3), emptyMap())
        assertThat(flat).isEqualTo(3 * PlayMoveRanker.GEM_POINTS)
        val fullBlue = PlayMoveRanker.plyPoints(
            extraMove = false, blue = 3, total = 3, lowerRow = 0, uncertain = false,
            blueFactor = PlayMoveRanker.FULL_BAR_BLUE_FACTOR, gemScore = 0,
        )
        assertThat(yellow).isGreaterThan(fullBlue)
        val emptyBlue = PlayMoveRanker.plyPoints(
            extraMove = false, blue = 3, total = 3, lowerRow = 0, uncertain = false, gemScore = 0,
        )
        assertThat(emptyBlue).isGreaterThan(yellow)
    }

    @Test
    fun straightFour_leavesARowArrow_andActivatingItClearsTheRow() {
        val board = board {
            this[3][0] = TileColor.R
            this[3][1] = TileColor.R
            this[3][2] = TileColor.R
            this[3][3] = TileColor.B
            this[2][3] = TileColor.R
            this[4][3] = TileColor.G
        }
        val made = SoloSwap().resolve(board, Move(2, 3, 3, 3))
        assertThat(made.extraMove).isTrue()
        assertThat(made.specialSpawn).isEqualTo("ARROW-row")
        var arrow: Pair<Int, Int>? = null
        made.board.forEachTile { tile ->
            if (tile.special == SpecialType.TWO_WAY_ARROW) arrow = tile.row to tile.col
        }
        assertThat(arrow).isNotNull()
        val (row, col) = arrow!!
        val neighborCol = if (col < 6) col + 1 else col - 1
        val fired = SoloSwap().resolve(made.board, Move(row, col, row, neighborCol).normalized())
        assertThat(fired.specialSpawn).contains("activate")
        assertThat(fired.total).isAtLeast(6)
        assertThat(fired.extraMove).isFalse()
    }

    @Test
    fun activateToggle_tapsOnlyTheRecognizedSoloButton() {
        val hud = HudObservation.solo(activateVisible = true)
        assertThat(hud.boosterTarget).isEqualTo("none")
        assertThat(SoloBooster.plan(hud, 1080, 2400, controlEnabled = false)).isNull()
        val tap = SoloBooster.plan(hud, 1080, 2400, controlEnabled = true)
        assertThat(tap).isNotNull()
        assertThat(tap!!.x).isGreaterThan(40f)
        assertThat(tap.y).isGreaterThan(800f)
        val pvp = HudObservation(mode = "pvp", soloLayout = false, activate = "yes")
        assertThat(SoloBooster.plan(pvp, 1080, 2400, controlEnabled = true)).isNull()
    }
}
