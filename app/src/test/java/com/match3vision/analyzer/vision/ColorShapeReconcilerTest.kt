package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import org.junit.Test

/**
 * Thorough reconciler coverage. Never invent PASS at pipeline level;
 * unknownCount must not be artificially reduced by reconciler.
 */
class ColorShapeReconcilerTest {

    @Test
    fun highConfContradiction_yieldsUnknown() {
        val v = ColorShapeReconciler.reconcile(
            color = TileColor.B,
            colorConf = 0.95f,
            shape = TileShape.CIRCLE,
            shapeConf = 0.92f,
            special = SpecialType.NONE,
        )
        assertThat(v.isUnknown).isTrue()
        assertThat(v.color).isEqualTo(TileColor.UNKNOWN)
        assertThat(v.shape).isEqualTo(TileShape.UNKNOWN)
    }

    @Test
    fun lowShapeConfDisagreement_trustsColorExpectedShape() {
        // Current reconciler: when shapeConf < RECONCILE_HIGH_CONFIDENCE, prefer color
        // and fill expected shape (does not invent PASS at pipeline gate level).
        val v = ColorShapeReconciler.reconcile(
            color = TileColor.G,
            colorConf = 0.90f,
            shape = TileShape.CIRCLE, // expects DIAMOND
            shapeConf = 0.65f, // < 0.70 high
            special = SpecialType.NONE,
        )
        assertThat(v.isUnknown).isFalse()
        assertThat(v.color).isEqualTo(TileColor.G)
        assertThat(v.shape).isEqualTo(TileShape.DIAMOND)
    }

    @Test
    fun bothBelowHigh_disagree_stillColorPreferred() {
        // Documents live branch order (else "mild disagreement" is unreachable when
        // color is known and shapeConf < high — color-prefer runs first).
        val v = ColorShapeReconciler.reconcile(
            color = TileColor.G,
            colorConf = 0.65f,
            shape = TileShape.CIRCLE,
            shapeConf = 0.65f,
            special = SpecialType.NONE,
        )
        assertThat(v.color).isEqualTo(TileColor.G)
        assertThat(v.shape).isEqualTo(TileShape.DIAMOND)
        assertThat(v.isUnknown).isFalse()
    }

    @Test
    fun consistentPairs_pass() {
        val pairs = listOf(
            TileColor.B to TileShape.STAR,
            TileColor.R to TileShape.CIRCLE,
            TileColor.Y to TileShape.TRIANGLE,
            TileColor.G to TileShape.DIAMOND,
            TileColor.P to TileShape.SQUARE,
            TileColor.O to TileShape.HEX,
        )
        for ((color, shape) in pairs) {
            val v = ColorShapeReconciler.reconcile(color, 0.9f, shape, 0.88f, SpecialType.NONE)
            assertThat(v.isUnknown).isFalse()
            assertThat(v.color).isEqualTo(color)
            assertThat(v.shape).isEqualTo(shape)
        }
    }

    @Test
    fun shapeUnknown_colorKnown_fillsExpectedShape() {
        val v = ColorShapeReconciler.reconcile(
            color = TileColor.P,
            colorConf = 0.9f,
            shape = TileShape.UNKNOWN,
            shapeConf = 0.3f,
            special = SpecialType.NONE,
        )
        assertThat(v.isUnknown).isFalse()
        assertThat(v.color).isEqualTo(TileColor.P)
        assertThat(v.shape).isEqualTo(TileShape.SQUARE)
    }

    @Test
    fun colorUnknown_shapeKnown_fillsExpectedColor() {
        val v = ColorShapeReconciler.reconcile(
            color = TileColor.UNKNOWN,
            colorConf = 0.2f,
            shape = TileShape.DIAMOND,
            shapeConf = 0.9f,
            special = SpecialType.NONE,
        )
        assertThat(v.isUnknown).isFalse()
        assertThat(v.shape).isEqualTo(TileShape.DIAMOND)
        assertThat(v.color).isEqualTo(TileColor.G)
    }

    @Test
    fun bothUnknown_staysUnknown() {
        val v = ColorShapeReconciler.reconcile(
            TileColor.UNKNOWN, 0.1f, TileShape.UNKNOWN, 0.1f, SpecialType.NONE,
        )
        assertThat(v.isUnknown).isTrue()
    }

    @Test
    fun contradiction_doesNotReduceUnknownCount_pipelineLevel() {
        // Board of solid greys → many unknowns; reconciler must not invent PASS cells
        val size = 70
        val pixels = IntArray(size * size) { SyntheticFrames.rgb(140, 140, 140) }
        for (i in 0..7) {
            val g = i * 10
            if (g < size) {
                for (y in 0 until size) pixels[y * size + g] = SyntheticFrames.GUTTER
                for (x in 0 until size) pixels[g * size + x] = SyntheticFrames.GUTTER
            }
        }
        val result = VisionPipeline().analyze(pixels, size, size, ContentRoi.full(size, size))
        assertThat(result.unknownCount).isGreaterThan(VisionThresholds.MAX_UNKNOWN_COUNT)
        assertThat(result.validation.isPass).isFalse()
    }

    @Test
    fun highConfContradiction_specialCleared() {
        val v = ColorShapeReconciler.reconcile(
            TileColor.R, 0.95f, TileShape.SQUARE, 0.95f, SpecialType.BOMB,
        )
        assertThat(v.isUnknown).isTrue()
        assertThat(v.special).isEqualTo(SpecialType.NONE)
    }
}
