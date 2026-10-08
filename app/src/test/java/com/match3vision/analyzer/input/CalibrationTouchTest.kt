package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [CalibrationTouch.recordRawTouch] is the function the calibration overlay
 * calls with MotionEvent raw coordinates. A point outside the calibration
 * radius is OBSERVED_MISMATCH on that production path.
 */
class CalibrationTouchTest {

    @Test
    fun productionRecordRawTouch_outsideTolerance_isObservedMismatch() {
        CoordinateSelfCheck.clear()
        val (expectedX, expectedY) = CalibrationTouch.expectedPoint(1080, 2400)
        val rec = CalibrationTouch.recordRawTouch(
            rawX = expectedX + 80f,
            rawY = expectedY,
            screenWidth = 1080,
            screenHeight = 2400,
            frameWidth = 1080,
            frameHeight = 2400,
            rotation = 0,
        )
        assertThat(rec.status).isEqualTo(CoordinateSelfCheck.STATUS_OBSERVED_MISMATCH)
        assertThat(rec.alignmentProven).isFalse()
        assertThat(rec.observedX).isEqualTo(expectedX + 80f)
        assertThat(CoordinateSelfCheck.allowsSingleMoveArm()).isFalse()
        assertThat(CoordinateSelfCheck.allowsContinuousStart()).isFalse()
        val ctrl = AutoPlayController()
        assertThat(ctrl.armSingleMove()).isFalse()
        assertThat(ctrl.onStartRequested()).isFalse()
        assertThat(ctrl.enableSwitch().isEnabled()).isFalse()
    }

    @Test
    fun productionRecordRawTouch_exactAndBoundary_unlockWithoutProvingAlignment() {
        CoordinateSelfCheck.clear()
        val (expectedX, expectedY) = CalibrationTouch.expectedPoint(1080, 2400)
        val exact = CalibrationTouch.recordRawTouch(
            rawX = expectedX,
            rawY = expectedY,
            screenWidth = 1080,
            screenHeight = 2400,
            frameWidth = 1080,
            frameHeight = 2400,
            rotation = 0,
        )
        assertThat(exact.status).isEqualTo(CoordinateSelfCheck.STATUS_MEASURED_WITHIN_TOLERANCE)
        assertThat(exact.alignmentProven).isFalse()
        assertThat(CoordinateSelfCheck.allowsContinuousStart()).isTrue()

        val boundary = CalibrationTouch.recordRawTouch(
            rawX = expectedX + CoordinateSelfCheck.MEASURED_TOLERANCE_PX,
            rawY = expectedY,
            screenWidth = 1080,
            screenHeight = 2400,
            frameWidth = 0,
            frameHeight = 0,
            rotation = 0,
        )
        assertThat(boundary.status).isEqualTo(CoordinateSelfCheck.STATUS_MEASURED_WITHIN_TOLERANCE)
        assertThat(boundary.alignmentProven).isFalse()

        val outside = CalibrationTouch.recordRawTouch(
            rawX = expectedX + CoordinateSelfCheck.MEASURED_TOLERANCE_PX + 1f,
            rawY = expectedY,
            screenWidth = 1080,
            screenHeight = 2400,
            frameWidth = 1080,
            frameHeight = 2400,
            rotation = 0,
        )
        assertThat(outside.status).isEqualTo(CoordinateSelfCheck.STATUS_MEASURED_WITHIN_TOLERANCE)
        assertThat(outside.observedX).isEqualTo(boundary.observedX)
        assertThat(CoordinateSelfCheck.allowsContinuousStart()).isTrue()
        assertThat(CoordinateSelfCheck.MEASURED_TOLERANCE_PX).isEqualTo(48f)
        assertThat(CoordinateSelfCheck.MEASURED_TOLERANCE_PX)
            .isNotEqualTo(HandMeasuredPvpCenters.TOLERANCE_PX)
    }

    @Test
    fun lumaOnlyRecord_staysUnproven_andDoesNotUnlock() {
        CoordinateSelfCheck.clear()
        val rec = CoordinateSelfCheck.record(
            expectedX = 100f,
            expectedY = 200f,
            screenWidth = 1080,
            screenHeight = 2400,
            frameWidth = 1080,
            frameHeight = 2400,
            rotation = 0,
            observedLuma = 180f,
        )
        assertThat(rec.status).isEqualTo(CoordinateSelfCheck.STATUS_RECORDED_UNPROVEN)
        assertThat(rec.alignmentProven).isFalse()
        assertThat(CoordinateSelfCheck.allowsContinuousStart()).isFalse()
        assertThat(CoordinateSelfCheck.allowsSingleMoveArm()).isFalse()
    }

    @Test
    fun measuredHit_survivesTheLiveMisses_untilClear() {
        CoordinateSelfCheck.clear()
        val (expectedX, expectedY) = CalibrationTouch.expectedPoint(1080, 2400)
        val hit = CalibrationTouch.recordRawTouch(
            rawX = expectedX + 9.2f,
            rawY = expectedY,
            screenWidth = 1080,
            screenHeight = 2400,
            frameWidth = 1080,
            frameHeight = 2400,
            rotation = 0,
        )
        assertThat(hit.status).isEqualTo(CoordinateSelfCheck.STATUS_MEASURED_WITHIN_TOLERANCE)
        val far = listOf(646f to 0f, 316f to 0f)
        for ((dx, dy) in far) {
            assertThat(
                CalibrationTarget.scoresTap(expectedX + dx, expectedY + dy, expectedX, expectedY),
            ).isFalse()
            val kept = CalibrationTouch.recordRawTouch(
                rawX = expectedX + dx,
                rawY = expectedY + dy,
                screenWidth = 1080,
                screenHeight = 2400,
                frameWidth = 1080,
                frameHeight = 2400,
                rotation = 0,
            )
            assertThat(kept.status).isEqualTo(CoordinateSelfCheck.STATUS_MEASURED_WITHIN_TOLERANCE)
            assertThat(kept.observedX).isEqualTo(hit.observedX)
        }
        assertThat(CalibrationTarget.scoresTap(expectedX + 80f, expectedY, expectedX, expectedY)).isTrue()
        assertThat(CalibrationTarget.SCORE_RADIUS_PX).isEqualTo(150f)
        assertThat(CalibrationTarget.AUTO_DISMISS_MS).isEqualTo(1_000L)
        CoordinateSelfCheck.clear()
        assertThat(CoordinateSelfCheck.allowsSingleMoveArm()).isFalse()
        assertThat(CoordinateSelfCheck.current()).isNull()
    }
}
