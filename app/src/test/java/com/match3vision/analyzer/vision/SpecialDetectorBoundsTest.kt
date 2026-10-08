package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Device fail-safe text was `HIBA: length=44409; index=-2`.
 * 131×339 = 44409. The unguarded arrow band uses height/5 as a horizontal
 * inset, so the first top-region x is -2. Detection must not throw.
 */
class SpecialDetectorBoundsTest {

    @Test
    fun length44409_indexMinus2_doesNotThrow() {
        val w = 131
        val h = 339
        assertThat(w * h).isEqualTo(44409)
        assertThat(SpecialDetector.unguardedArrowIndex(w, h)).isEqualTo(-2)
        val result = SpecialDetector().detect(IntArray(w * h), w, h)
        assertThat(result.special).isEqualTo(SpecialType.NONE)
    }

    @Test
    fun truncatedBuffer_doesNotThrow() {
        val result = SpecialDetector().detect(IntArray(10), 131, 339)
        assertThat(result.special).isEqualTo(SpecialType.NONE)
    }
}
