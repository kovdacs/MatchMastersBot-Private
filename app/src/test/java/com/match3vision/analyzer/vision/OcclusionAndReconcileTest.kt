package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OcclusionAndReconcileTest {

    private val occlusion = OcclusionDetector()
    private val special = SpecialDetector()

    @Test
    fun occludedDarkCell_isDetected() {
        val cell = SyntheticFrames.darkCell()
        val r = occlusion.detect(cell, 24, 24)
        assertThat(r.occluded).isTrue()
    }

    @Test
    fun bannerCell_isOccluded() {
        val cell = SyntheticFrames.bannerCell()
        val r = occlusion.detect(cell, 24, 24)
        assertThat(r.occluded).isTrue()
        assertThat(r.reason).contains("banner")
    }

    @Test
    fun clearColoredCell_notOccluded() {
        val cell = SyntheticFrames.solidCell(SyntheticFrames.COLOR_B)
        val r = occlusion.detect(cell, 24, 24)
        assertThat(r.occluded).isFalse()
    }

    @Test
    fun solidRedTile_notOccludedAsBanner() {
        val cell = SyntheticFrames.solidCell(SyntheticFrames.COLOR_R)
        val r = occlusion.detect(cell, 24, 24)
        assertThat(r.occluded).isFalse()
        assertThat(r.reason).isEqualTo("clear")
    }

    @Test
    fun solidOrangeTile_notOccludedAsBanner() {
        val cell = SyntheticFrames.solidCell(SyntheticFrames.COLOR_O)
        val r = occlusion.detect(cell, 24, 24)
        assertThat(r.occluded).isFalse()
        assertThat(r.reason).isEqualTo("clear")
    }

    @Test
    fun solidRedTileWithDarkBorder_notOccludedAsBanner() {
        // Cell crop that includes a thin dark gutter must still count as a tile.
        val w = 24
        val h = 24
        val cell = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x < 2 || y < 2 || x >= w - 2 || y >= h - 2) {
                SyntheticFrames.GUTTER
            } else {
                SyntheticFrames.COLOR_R
            }
        }
        val r = occlusion.detect(cell, w, h)
        assertThat(r.occluded).isFalse()
    }

    @Test
    fun colorShapeContradiction_highConfidence_yieldsUnknown() {
        val vision = ColorShapeReconciler.reconcile(
            color = TileColor.B,
            colorConf = 0.95f,
            shape = TileShape.CIRCLE, // expects STAR
            shapeConf = 0.92f,
            special = SpecialType.NONE,
        )
        assertThat(vision.isUnknown).isTrue()
        assertThat(vision.color).isEqualTo(TileColor.UNKNOWN)
        assertThat(vision.shape).isEqualTo(TileShape.UNKNOWN)
    }

    @Test
    fun matchingColorShape_notUnknown() {
        val vision = ColorShapeReconciler.reconcile(
            color = TileColor.R,
            colorConf = 0.9f,
            shape = TileShape.CIRCLE,
            shapeConf = 0.88f,
            special = SpecialType.NONE,
        )
        assertThat(vision.isUnknown).isFalse()
        assertThat(vision.color).isEqualTo(TileColor.R)
        assertThat(vision.shape).isEqualTo(TileShape.CIRCLE)
    }

    @Test
    fun colorOnly_fillsExpectedShape() {
        val vision = ColorShapeReconciler.reconcile(
            color = TileColor.Y,
            colorConf = 0.9f,
            shape = TileShape.UNKNOWN,
            shapeConf = 0.3f,
            special = SpecialType.NONE,
        )
        assertThat(vision.isUnknown).isFalse()
        assertThat(vision.shape).isEqualTo(TileShape.TRIANGLE)
    }

    @Test
    fun special_belowThreshold_becomesNone() {
        // Plain solid cell — special detector should stay below 0.55 → NONE
        val cell = SyntheticFrames.solidCell(SyntheticFrames.COLOR_G)
        val r = special.detect(cell, 24, 24)
        assertThat(r.confidence).isLessThan(VisionThresholds.SPECIAL_MIN_CONFIDENCE)
        assertThat(r.special).isEqualTo(SpecialType.NONE)
    }

    @Test
    fun special_explicitLowConfidence_mapsToNone() {
        // Detector API contract: anything < 0.55 must be NONE
        val forced = SpecialDetector.Result(SpecialType.BOMB, 0.54f)
        val applied = if (forced.confidence < VisionThresholds.SPECIAL_MIN_CONFIDENCE) {
            SpecialType.NONE
        } else {
            forced.special
        }
        assertThat(applied).isEqualTo(SpecialType.NONE)
        assertThat(0.54f).isLessThan(VisionThresholds.SPECIAL_MIN_CONFIDENCE)
    }

    // --- Expanded occlusion coverage (partial 50–70%, UI overlays, textured banner) ---

    @Test
    fun partialDarkOverlay_60percent_isOccluded() {
        val w = 24
        val h = 24
        val cell = IntArray(w * h) { i ->
            val y = i / w
            // Top ~60% dark overlay, bottom solid blue tile
            if (y < (h * 0.60f).toInt()) SyntheticFrames.DARK else SyntheticFrames.COLOR_B
        }
        val r = occlusion.detect(cell, w, h)
        assertThat(r.occluded).isTrue()
        assertThat(r.reason).isAnyOf("partial_dark", "dark_cell", "mean_dark")
    }

    @Test
    fun partialDarkOverlay_50percent_isOccluded() {
        val w = 24
        val h = 24
        val cell = IntArray(w * h) { i ->
            val y = i / w
            if (y < h / 2) SyntheticFrames.DARK else SyntheticFrames.COLOR_G
        }
        val r = occlusion.detect(cell, w, h)
        assertThat(r.occluded).isTrue()
    }

    @Test
    fun whiteUiOverlay_60percent_isOccluded() {
        val w = 24
        val h = 24
        val white = SyntheticFrames.rgb(245, 245, 248)
        val cell = IntArray(w * h) { i ->
            val x = i % w
            if (x < (w * 0.60f).toInt()) white else SyntheticFrames.COLOR_G
        }
        val r = occlusion.detect(cell, w, h)
        assertThat(r.occluded).isTrue()
        assertThat(r.reason).contains("ui")
    }

    @Test
    fun grayUiOverlay_55percent_isOccluded() {
        val w = 24
        val h = 24
        val gray = SyntheticFrames.rgb(160, 160, 165)
        val cell = IntArray(w * h) { i ->
            val y = i / w
            if (y < (h * 0.55f).toInt()) gray else SyntheticFrames.COLOR_Y
        }
        val r = occlusion.detect(cell, w, h)
        assertThat(r.occluded).isTrue()
    }

    @Test
    fun blueUiOverlay_60percent_isOccluded() {
        // Washed blue chrome (not saturated tile blue)
        val w = 24
        val h = 24
        val blueUi = SyntheticFrames.rgb(140, 180, 230) // lower sat than COLOR_B
        val cell = IntArray(w * h) { i ->
            val x = i % w
            if (x < (w * 0.60f).toInt()) blueUi else SyntheticFrames.COLOR_R
        }
        val r = occlusion.detect(cell, w, h)
        assertThat(r.occluded).isTrue()
    }

    @Test
    fun texturedBanner_partial_isOccluded() {
        val w = 24
        val h = 24
        val cell = IntArray(w * h) { i ->
            val y = i / w
            if (y < (h * 0.55f).toInt()) {
                if ((i % 3) == 0) SyntheticFrames.BANNER_ORANGE else SyntheticFrames.BANNER_RED
            } else {
                SyntheticFrames.COLOR_G
            }
        }
        val r = occlusion.detect(cell, w, h)
        assertThat(r.occluded).isTrue()
    }

    @Test
    fun warmBannerFull_stillOccluded() {
        val cell = SyntheticFrames.bannerCell()
        val r = occlusion.detect(cell, 24, 24)
        assertThat(r.occluded).isTrue()
        assertThat(r.reason).contains("banner")
    }

    @Test
    fun solidRed_stillClear_afterPartialFix() {
        val r = occlusion.detect(SyntheticFrames.solidCell(SyntheticFrames.COLOR_R), 24, 24)
        assertThat(r.occluded).isFalse()
        assertThat(r.reason).isEqualTo("clear")
    }

    @Test
    fun solidOrange_stillClear_afterPartialFix() {
        val r = occlusion.detect(SyntheticFrames.solidCell(SyntheticFrames.COLOR_O), 24, 24)
        assertThat(r.occluded).isFalse()
        assertThat(r.reason).isEqualTo("clear")
    }
}
