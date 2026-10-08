package com.match3vision.analyzer.input

import com.match3vision.analyzer.capture.ScreenMeasurement
import com.match3vision.analyzer.capture.ScreenMetricsSource

/**
 * Live facts re-read inside [AccessibilityGestureExecutor.dispatchChecked].
 *
 * A null boolean means the fact could not be read. The dispatcher fails closed.
 * [screen] is an independent display measurement, never the capture bitmap.
 */
data class LiveDispatchProbe(
    val inputEnabled: () -> Boolean?,
    val stopped: () -> Boolean?,
    val captureReady: () -> Boolean?,
    val screen: () -> ScreenMeasurement,
) {
    companion object {
        fun unavailable(): LiveDispatchProbe = LiveDispatchProbe(
            inputEnabled = { null },
            stopped = { null },
            captureReady = { null },
            screen = { ScreenMeasurement.unavailable() },
        )
    }
}

/**
 * Process-wide readers the bubble installs from Android services.
 * Until [install] runs, every read fails closed.
 */
object ProductionLiveReaders {
    @Volatile
    var screenSource: ScreenMetricsSource = ScreenMetricsSource { ScreenMeasurement.unavailable() }

    @Volatile
    var readCaptureReady: () -> Boolean? = { null }

    @Volatile
    var readStopped: () -> Boolean? = { null }

    fun probe(enableSwitch: InputEnableSwitch): LiveDispatchProbe = LiveDispatchProbe(
        inputEnabled = { enableSwitch.isEnabled() },
        stopped = { readStopped() },
        captureReady = { readCaptureReady() },
        screen = { screenSource.measure() },
    )

    fun reset() {
        screenSource = ScreenMetricsSource { ScreenMeasurement.unavailable() }
        readCaptureReady = { null }
        readStopped = { null }
    }
}
