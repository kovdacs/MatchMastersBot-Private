package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The tolerance is derived from the hand column pitch. It is not the row-2 error.
 */
class HandMeasuredOracleTest {
    @Test
    fun tolerance_isMinColumnPitchOverFive_row6IsMeasured() {
        val xs = HandMeasuredPvpCenters.columnCenterX
        val pitches = FloatArray(xs.size - 1) { i -> xs[i + 1] - xs[i] }
        var min = pitches[0]
        for (p in pitches) if (p < min) min = p
        assertThat(min).isEqualTo(149f)
        assertThat(HandMeasuredPvpCenters.MIN_COLUMN_PITCH_PX).isEqualTo(149f)
        assertThat(HandMeasuredPvpCenters.TOLERANCE_PX).isEqualTo(149f / 5f)
        assertThat(HandMeasuredPvpCenters.TOLERANCE_PX).isNotEqualTo(27.5f)
        assertThat(HandMeasuredPvpCenters.TOLERANCE_PX).isLessThan(149f / 2f)
        // Honest row-2 margin: 27.5 px is inside 29.8 and is not why 29.8 was chosen.
        assertThat(27.5f).isLessThan(HandMeasuredPvpCenters.TOLERANCE_PX)
        assertThat(HandMeasuredPvpCenters.rowCenterY[2]).isEqualTo(1563f)
        assertThat(HandMeasuredPvpCenters.rowCenterY[6]).isEqualTo(2157f)
        assertThat(HandMeasuredPvpCenters.rowCenterY[6]).isNotEqualTo(2165f)
        assertThat(HandMeasuredPvpCenters.rowCenterY[6]).isNotEqualTo(2171f)
    }

    @Test
    fun tolerance_doesNotDependOnTheObservedRow2Error() {
        val observedRow2ErrorPx = 27.5f
        assertThat(HandMeasuredPvpCenters.TOLERANCE_PX)
            .isEqualTo(HandMeasuredPvpCenters.MIN_COLUMN_PITCH_PX / 5f)
        assertThat(HandMeasuredPvpCenters.MIN_COLUMN_PITCH_PX).isEqualTo(149f)
        assertThat(HandMeasuredPvpCenters.TOLERANCE_PX).isEqualTo(149f / 5f)
        assertThat(HandMeasuredPvpCenters.TOLERANCE_PX).isNotEqualTo(observedRow2ErrorPx)
        assertThat(HandMeasuredPvpCenters.TOLERANCE_PX - observedRow2ErrorPx)
            .isWithin(0.01f).of(2.3f)
        assertThat(HandMeasuredPvpCenters.TOLERANCE_PX).isLessThan(149f / 2f)
    }
}
