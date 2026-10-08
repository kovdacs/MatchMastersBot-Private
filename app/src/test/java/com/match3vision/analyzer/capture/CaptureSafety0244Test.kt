package com.match3vision.analyzer.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer

class CaptureSafety0244Test {

    @Test
    fun independentScreen_prefersMaximumWindow_andIgnoresAFrameSizedArgument() {
        val chosen = IndependentScreenMetrics.choose(
            maximumWindowWidth = 1080,
            maximumWindowHeight = 2400,
            currentWindowWidth = 1080,
            currentWindowHeight = 2200,
            realWidth = 1080,
            realHeight = 2340,
            densityDpi = 440,
            rotation = 0,
        )
        assertThat(chosen.widthPx).isEqualTo(1080)
        assertThat(chosen.heightPx).isEqualTo(2400)
        assertThat(chosen.source).isEqualTo(ScreenMeasurement.SOURCE_MAXIMUM_WINDOW)
        assertThat(chosen.valid).isTrue()
        val current = IndependentScreenMetrics.choose(0, 0, 1080, 2400, 100, 200, 320, 1)
        assertThat(current.source).isEqualTo(ScreenMeasurement.SOURCE_CURRENT_WINDOW)
        val real = IndependentScreenMetrics.choose(0, 0, 0, 0, 720, 1600, 320, 0)
        assertThat(real.source).isEqualTo(ScreenMeasurement.SOURCE_REAL_METRICS)
        val missing = IndependentScreenMetrics.choose(0, 0, 0, 0, 0, 0, 0, -1)
        assertThat(missing.valid).isFalse()
        assertThat(missing.source).isEqualTo(ScreenMeasurement.SOURCE_UNAVAILABLE)
    }

    @Test
    fun rgbaUnpack_skipsRowPadding_andRejectsShortStride() {
        val width = 2
        val height = 2
        val pixelStride = 4
        val rowStride = 16
        val raw = ByteArray(rowStride * height)
        fun put(row: Int, col: Int, r: Int, g: Int, b: Int, a: Int) {
            val pos = row * rowStride + col * pixelStride
            raw[pos] = r.toByte()
            raw[pos + 1] = g.toByte()
            raw[pos + 2] = b.toByte()
            raw[pos + 3] = a.toByte()
        }
        put(0, 0, 10, 20, 30, 255)
        put(0, 1, 1, 2, 3, 255)
        raw[8] = 0xAB.toByte()
        put(1, 0, 40, 50, 60, 255)
        put(1, 1, 7, 8, 9, 128)
        val unpacked = RgbaBufferUnpack.unpack(
            ByteBuffer.wrap(raw),
            width,
            height,
            rowStride,
            pixelStride,
        )
        assertThat(unpacked).isNotNull()
        assertThat(unpacked!![0]).isEqualTo((255 shl 24) or (10 shl 16) or (20 shl 8) or 30)
        assertThat(unpacked[1]).isEqualTo((255 shl 24) or (1 shl 16) or (2 shl 8) or 3)
        assertThat(unpacked[2]).isEqualTo((255 shl 24) or (40 shl 16) or (50 shl 8) or 60)
        assertThat(unpacked[3] ushr 24).isEqualTo(128)
        assertThat(
            RgbaBufferUnpack.unpack(ByteBuffer.wrap(raw), width, height, rowStride = 4, pixelStride = 4),
        ).isNull()
        assertThat(
            RgbaBufferUnpack.unpack(ByteBuffer.allocate(8), width, height, rowStride, pixelStride),
        ).isNull()
    }

    @Test
    fun cadence_reportsIntervals() {
        val cadence = FrameCadence()
        assertThat(cadence.summary()).contains("no interval")
        cadence.record(1_000L)
        cadence.record(1_200L)
        cadence.record(1_500L)
        assertThat(cadence.intervalsMs()).containsExactly(200L, 300L).inOrder()
        assertThat(cadence.summary()).contains("medianMs=300")
    }

    @Test
    fun consentReuse_andForegroundRequirement() {
        assertThat(CaptureConsent.reuseRefusal(alreadyCapturing = true, hasResultData = true))
            .contains("reuse")
        assertThat(CaptureConsent.reuseRefusal(alreadyCapturing = false, hasResultData = false))
            .contains("missing")
        assertThat(CaptureConsent.reuseRefusal(alreadyCapturing = false, hasResultData = true))
            .isNull()
        assertThat(MediaProjectionStartup.requiresForegroundServiceFirst(29)).isTrue()
        assertThat(MediaProjectionStartup.requiresForegroundServiceFirst(28)).isFalse()
        assertThat(MediaProjectionStartup.FOREGROUND_SERVICE_TYPE).isEqualTo("mediaProjection")
        assertThat(MediaProjectionStartup.RESTRICTED_SETTINGS_STEP).contains("restricted settings")
    }
}
