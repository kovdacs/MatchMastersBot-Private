package com.match3vision.analyzer.hud

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.input.FiveMoveSession
import com.match3vision.analyzer.vision.RealFrameLoader
import org.junit.Test

/**
 * The 0.27.8 pip row is y=942, x=402, pitch 68. These solo frames read 9 there.
 * Painting the empty slot and one filled slot on that same row yields 10 and 8.
 */
class OwnerPips0284Test {
    @Test
    fun geometry_isThe0278Row() {
        assertThat(HudReader.CIRCLE_ROW_Y).isEqualTo(942)
        assertThat(HudReader.CIRCLE_ORIGIN_X).isEqualTo(402)
        assertThat(HudReader.CIRCLE_PITCH).isEqualTo(68)
        assertThat(HudReader.CIRCLE_COUNT).isEqualTo(10)
        assertThat(HudReader.circleX(0)).isEqualTo(402)
        assertThat(HudReader.circleX(9)).isEqualTo(402 + 9 * 68)
    }

    @Test
    fun ownerFrames_readTenNineAndEight() {
        val nine = load("hud_solo/f_06.png")
        assertThat(HudReader.read(nine, 1080, 2400).circlesBright).isEqualTo(9)
        assertThat(HudReader.read(load("hud_solo/f_03.png"), 1080, 2400).circlesBright).isEqualTo(9)
        assertThat(HudReader.read(load("hud_solo/f_015.png"), 1080, 2400).circlesBright).isEqualTo(9)

        val ten = nine.copyOf()
        paint(ten, 402 + 9 * 68, 942, 80, 220, 230)
        assertThat(HudReader.read(ten, 1080, 2400).circlesBright).isEqualTo(10)

        val eight = nine.copyOf()
        paint(eight, 402, 942, 40, 20, 70)
        assertThat(HudReader.read(eight, 1080, 2400).circlesBright).isEqualTo(8)
    }

    @Test
    fun zeroAtStart_doesNotStop_untilASwipeAndABrightRow() {
        val session = FiveMoveSession()
        session.arm(0L)
        assertThat(session.noteCircles(true, 0, true, 1_000L, 1L)).isNull()
        assertThat(session.noteCircles(true, 0, true, 1_100L, 2L)).isNull()
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(session.stopReason).isEmpty()
        assertThat(session.noteCircles(true, 10, true, 2_000L, 3L)).isNull()
        assertThat(session.noteCircles(true, 0, true, 3_000L, 4L)).isNull()
        assertThat(session.noteCircles(true, 0, true, 3_100L, 5L)).isNull()
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        val go = session.requestDispatch(
            FiveMoveSession.Gates(
                nowMs = 4_000L,
                a11yConnected = true,
                selfCheckMeasured = true,
                overlayCollapsed = true,
                overlayOutsideRoi = true,
                visionPass = true,
                frameFresh = true,
                ownUi = false,
                msSinceCollapse = FiveMoveSession.MIN_POST_COLLAPSE_MS,
                roiPlausible = true,
                frameSequence = 6L,
            ),
        ) as FiveMoveSession.Decision.Go
        session.consumePermit(go.permit)
        session.noteGesture(
            FiveMoveSession.GestureFact(
                startedAtMs = 4_000L,
                nowMs = 4_050L,
                callbackCompleted = true,
                cancelled = false,
                cells = "(0,0)↔(0,1)",
                fromX = 1f,
                fromY = 1f,
                toX = 2f,
                toY = 1f,
                beforeHash = 1L,
                beforeUnknown = 0,
            ),
        )
        assertThat(session.swipesDispatched).isEqualTo(1)
        assertThat(session.noteCircles(true, 9, true, 5_000L, 7L)).isNull()
        assertThat(session.noteCircles(true, 0, true, 6_000L, 8L)).isNull()
        val spent = session.noteCircles(true, 0, true, 6_100L, 9L)
        assertThat(spent).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(session.stopReason).contains("moves spent")
    }

    private fun load(name: String): IntArray {
        val frame = RealFrameLoader.loadFromResource(name)
        assertThat(frame).isNotNull()
        return frame!!.pixels
    }

    private fun paint(pixels: IntArray, cx: Int, cy: Int, r: Int, g: Int, b: Int) {
        val color = HudReader.argb(r, g, b)
        for (y in (cy - 10)..(cy + 10)) {
            for (x in (cx - 10)..(cx + 10)) {
                pixels[y * 1080 + x] = color
            }
        }
    }
}
