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
}
