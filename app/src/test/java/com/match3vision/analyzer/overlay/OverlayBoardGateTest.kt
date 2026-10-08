package com.match3vision.analyzer.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The expanded bubble on Test 0 covered the left columns. A collapsed chip
 * on a 1080×2400 phone must sit above the board. A bad mapping stays HOLD.
 * These tests do not dispatch.
 */
class OverlayBoardGateTest {

    private val boardLeft = 0
    private val boardTop = 1190
    private val boardRight = 1080
    private val boardBottom = 2240

    @Test
    fun expandedPanel_intersectsBoard_andHolds() {
        val overlay = OverlayPlacement.Rect(0, 100, 500, 1990)
        val decision = OverlayBoardGate.evaluate(
            overlay = overlay,
            boardLeft = boardLeft,
            boardTop = boardTop,
            boardRight = boardRight,
            boardBottom = boardBottom,
            frameWidth = 1080,
            frameHeight = 2400,
            screenWidth = 1080,
            screenHeight = 2400,
            rotation = 0,
        )
        assertThat(decision.allowAnalysis).isFalse()
        assertThat(decision.reason).isEqualTo(OverlayBoardGate.HOLD_INTERSECTS)
    }

    @Test
    fun collapsedChip_onPhoneDensity_doesNotIntersect() {
        val chip = OverlayPlacement.collapsedChipPx(1080, 2400, 2.75f)
        assertThat(chip.bottom).isLessThan(boardTop)
        val decision = OverlayBoardGate.evaluate(
            overlay = chip,
            boardLeft = boardLeft,
            boardTop = boardTop,
            boardRight = boardRight,
            boardBottom = boardBottom,
            frameWidth = 1080,
            frameHeight = 2400,
            screenWidth = 1080,
            screenHeight = 2400,
            rotation = 0,
        )
        assertThat(decision.allowAnalysis).isTrue()
    }

    @Test
    fun sharedEdge_isNotAnIntersection() {
        val overlay = OverlayPlacement.Rect(0, 0, 400, boardTop)
        val decision = OverlayBoardGate.evaluate(
            overlay = overlay,
            boardLeft = boardLeft,
            boardTop = boardTop,
            boardRight = boardRight,
            boardBottom = boardBottom,
            frameWidth = 1080,
            frameHeight = 2400,
            screenWidth = 1080,
            screenHeight = 2400,
            rotation = 0,
        )
        assertThat(decision.allowAnalysis).isTrue()
    }

    @Test
    fun sizeMismatch_refuses() {
        val chip = OverlayPlacement.collapsedChipPx(1080, 2400, 2.75f)
        val decision = OverlayBoardGate.evaluate(
            overlay = chip,
            boardLeft = boardLeft,
            boardTop = boardTop,
            boardRight = boardRight,
            boardBottom = boardBottom,
            frameWidth = 1080,
            frameHeight = 1920,
            screenWidth = 1080,
            screenHeight = 2400,
            rotation = 0,
        )
        assertThat(decision.allowAnalysis).isFalse()
        assertThat(decision.reason).isEqualTo(OverlayBoardGate.HOLD_UNMAPPED)
    }

    @Test
    fun nonzeroAndUnknownRotation_refuse() {
        val chip = OverlayPlacement.collapsedChipPx(1080, 2400, 2.75f)
        for (rotation in intArrayOf(1, -1)) {
            val decision = OverlayBoardGate.evaluate(
                overlay = chip,
                boardLeft = boardLeft,
                boardTop = boardTop,
                boardRight = boardRight,
                boardBottom = boardBottom,
                frameWidth = 1080,
                frameHeight = 2400,
                screenWidth = 1080,
                screenHeight = 2400,
                rotation = rotation,
            )
            assertThat(decision.allowAnalysis).isFalse()
            assertThat(decision.reason).isEqualTo(OverlayBoardGate.HOLD_UNMAPPED)
        }
    }

    @Test
    fun unknownOverlay_refuses() {
        val decision = OverlayBoardGate.evaluate(
            overlay = null,
            boardLeft = boardLeft,
            boardTop = boardTop,
            boardRight = boardRight,
            boardBottom = boardBottom,
            frameWidth = 1080,
            frameHeight = 2400,
            screenWidth = 1080,
            screenHeight = 2400,
            rotation = 0,
        )
        assertThat(decision.allowAnalysis).isFalse()
        assertThat(decision.reason).isEqualTo(OverlayBoardGate.HOLD_UNKNOWN)
    }

    @Test
    fun frameBeforeCollapseDelay_isNotEligible() {
        assertThat(
            OverlayPlacement.frameShowsCollapsedOverlay(
                frameTimestampMs = 1_000L,
                collapseWallMs = 800L,
            ),
        ).isFalse()
        assertThat(
            OverlayPlacement.frameShowsCollapsedOverlay(
                frameTimestampMs = 1_300L,
                collapseWallMs = 800L,
            ),
        ).isTrue()
        assertThat(
            OverlayPlacement.frameShowsCollapsedOverlay(
                frameTimestampMs = 5_000L,
                collapseWallMs = 0L,
            ),
        ).isFalse()
    }
}
