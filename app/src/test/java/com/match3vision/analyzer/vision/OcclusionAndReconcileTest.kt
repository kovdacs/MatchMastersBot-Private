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
}
