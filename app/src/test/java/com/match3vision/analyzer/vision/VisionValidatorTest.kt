package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VisionValidatorTest {

    private val validator = VisionValidator()

    @Test
    fun cleanBoard_gatePass() {
        val result = validator.validate(
            boardConfidence = 0.97f,
            gridConfidence = 0.99f,
            unknownCount = 0,
        )
        assertThat(result).isEqualTo(ValidationResult.Pass)
        assertThat(result.isPass).isTrue()
    }

    @Test
    fun unknownGreaterThanOne_gateHold() {
        val result = validator.validate(
            boardConfidence = 0.99f,
            gridConfidence = 0.99f,
            unknownCount = 2,
        )
        assertThat(result.isPass).isFalse()
        assertThat(result).isInstanceOf(ValidationResult.Hold::class.java)
        val hold = result as ValidationResult.Hold
        assertThat(hold.reason).contains("unknownCount")
        assertThat(hold.reason).contains("Decision AI blocked")
    }

    @Test
    fun lowBoardConfidence_gateHold() {
        val result = validator.validate(0.90f, 0.99f, 0)
        assertThat(result.isPass).isFalse()
        assertThat((result as ValidationResult.Hold).reason).contains("board confidence")
    }

    @Test
    fun lowGridConfidence_gateHold() {
        val result = validator.validate(0.99f, 0.90f, 0)
        assertThat(result.isPass).isFalse()
        assertThat((result as ValidationResult.Hold).reason).contains("grid confidence")
    }

    @Test
    fun oneUnknown_stillPass() {
        val result = validator.validate(0.96f, 0.98f, 1)
        assertThat(result).isEqualTo(ValidationResult.Pass)
    }

    @Test
    fun integrity_thresholdConstantsUnchanged() {
        assertThat(VisionThresholds.MIN_GRID_CONFIDENCE).isEqualTo(0.98f)
        assertThat(VisionThresholds.MIN_BOARD_CONFIDENCE).isEqualTo(0.95f)
        assertThat(VisionThresholds.MAX_UNKNOWN_COUNT).isEqualTo(1)
    }

    @Test
    fun integrity_gridJustBelowGate_holds() {
        val result = validator.validate(0.99f, 0.979f, 0)
        assertThat(result.isPass).isFalse()
    }

    @Test
    fun integrity_boardJustBelowGate_holds() {
        val result = validator.validate(0.949f, 0.99f, 0)
        assertThat(result.isPass).isFalse()
    }

    @Test
    fun integrity_unknownCountTwo_holdsEvenIfConfidencesPerfect() {
        val result = validator.validate(1f, 1f, 2)
        assertThat(result.isPass).isFalse()
    }

    @Test
    fun integrity_exactGateBoundaries_pass() {
        assertThat(validator.validate(0.95f, 0.98f, 1)).isEqualTo(ValidationResult.Pass)
        assertThat(validator.validate(0.95f, 0.98f, 0)).isEqualTo(ValidationResult.Pass)
    }

    @Test
    fun integrity_noForcedPassWhenAnyGateFails() {
        val cases = listOf(
            Triple(0.94f, 0.99f, 0),
            Triple(0.99f, 0.97f, 0),
            Triple(0.99f, 0.99f, 2),
        )
        for ((b, g, u) in cases) {
            assertThat(validator.validate(b, g, u).isPass).isFalse()
        }
    }

    @Test
    fun threeIndependentHoldModes_noBypass() {
        val holdGrid = validator.validate(0.99f, 0.97f, 0)
        assertThat(holdGrid.isPass).isFalse()
        assertThat((holdGrid as ValidationResult.Hold).reason).contains("grid confidence")

        val holdBoard = validator.validate(0.94f, 0.99f, 0)
        assertThat(holdBoard.isPass).isFalse()
        assertThat((holdBoard as ValidationResult.Hold).reason).contains("board confidence")

        val holdUnk = validator.validate(0.99f, 0.99f, 2)
        assertThat(holdUnk.isPass).isFalse()
        assertThat((holdUnk as ValidationResult.Hold).reason).contains("unknownCount")

        assertThat(validator.validate(0.90f, 0.90f, 5).isPass).isFalse()
    }

    @Test
    fun highUnknown_reportedBeforeBoardConf() {
        // Live HOLD often had boardConf=0 masking unk=42; unknown is checked first now.
        val result = validator.validate(
            boardConfidence = 0.0f,
            gridConfidence = 0.99f,
            unknownCount = 42,
        )
        assertThat(result.isPass).isFalse()
        val hold = result as ValidationResult.Hold
        assertThat(hold.reason).contains("unknownCount")
        assertThat(hold.reason).doesNotContain("board confidence")
    }
}
