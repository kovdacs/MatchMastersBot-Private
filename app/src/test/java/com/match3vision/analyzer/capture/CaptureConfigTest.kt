package com.match3vision.analyzer.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class CaptureConfigTest {

    @Test
    fun defaultTargetFps_isFive() {
        val config = CaptureConfig()
        assertThat(config.targetFps).isEqualTo(5)
        assertThat(config.frameIntervalMs).isEqualTo(200L)
    }

    @Test
    fun clampFps_clampsBelowMinimum() {
        assertThat(CaptureConfig.clampFps(0)).isEqualTo(CaptureConfig.MIN_FPS)
        assertThat(CaptureConfig.clampFps(-10)).isEqualTo(CaptureConfig.MIN_FPS)
    }

    @Test
    fun clampFps_clampsAboveMaximum() {
        assertThat(CaptureConfig.clampFps(100)).isEqualTo(CaptureConfig.MAX_FPS)
        assertThat(CaptureConfig.clampFps(CaptureConfig.MAX_FPS + 1))
            .isEqualTo(CaptureConfig.MAX_FPS)
    }

    @Test
    fun clampFps_preservesValidValues() {
        assertThat(CaptureConfig.clampFps(1)).isEqualTo(1)
        assertThat(CaptureConfig.clampFps(5)).isEqualTo(5)
        assertThat(CaptureConfig.clampFps(15)).isEqualTo(15)
    }

    @Test
    fun withClampedFps_buildsValidConfigFromOutOfRange() {
        val config = CaptureConfig.withClampedFps(99)
        assertThat(config.targetFps).isEqualTo(CaptureConfig.MAX_FPS)
        assertThat(config.frameIntervalMs).isEqualTo(1000L / CaptureConfig.MAX_FPS)
    }

    @Test
    fun constructor_rejectsOutOfRangeFps() {
        assertThrows(IllegalArgumentException::class.java) {
            CaptureConfig(targetFps = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CaptureConfig(targetFps = 16)
        }
    }
}
