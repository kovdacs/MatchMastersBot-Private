package com.match3vision.analyzer.capture

/**
 * One stop wins. A system MediaProjection onStop must not call projection.stop()
 * again, and an explicit stop must not run the system path a second time.
 * Either path removes the foreground notification and asks the service to stop.
 */
class CaptureStopPolicy {
    private var claimed = false
    var projectionStopCalls: Int = 0
        private set
    var foregroundRemoved: Int = 0
        private set
    var stopSelfCalls: Int = 0
        private set

    fun onSystemStop(removeForeground: () -> Unit, stopSelf: () -> Unit) {
        if (claimed) return
        claimed = true
        removeForeground()
        foregroundRemoved += 1
        stopSelf()
        stopSelfCalls += 1
    }

    fun onExplicitStop(
        stopProjection: () -> Unit,
        removeForeground: () -> Unit,
        stopSelf: () -> Unit,
    ) {
        if (claimed) return
        claimed = true
        stopProjection()
        projectionStopCalls += 1
        removeForeground()
        foregroundRemoved += 1
        stopSelf()
        stopSelfCalls += 1
    }
}
