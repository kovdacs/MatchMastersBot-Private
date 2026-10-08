package com.match3vision.analyzer.input

import com.match3vision.analyzer.capture.FrameSequenceGate

/**
 * Production composition rules for the live auto-play chain.
 *
 * The device loop is:
 * MainActivity INDÍTÁS
 * → CaptureService (entire display on API 34+)
 * → FloatingBubbleService.ACTION_START_LOOP
 * → [ProductionInstall] AccessibilityGestureExecutor on [AutoPlaySession]
 * → ensureLoopRunning measures [ProductionCycleContext]
 * → AutoPlayController.runCycleIfActive
 * → InputLoopController.runAnalyzeAndMaybeInput
 * → AutomaticInputEngine.tryExecute
 * → TouchCoordinateMapper + [FrameScreenCoordinatePolicy] + CoordinateBounds
 * → AccessibilityGestureExecutor.dispatch
 * → MatchMastersAccessibilityService.dispatchGesture
 * → verify only on a new fresh frame ([VerificationPolicy])
 *
 * [RecordingInputGestureExecutor] and [ShellInputGestureExecutor] are refused.
 * A missing [RuntimeCycleContext] or `simulated = true` is refused on the
 * production executor so evaluateGate defaults (a11y true, age 0) cannot dispatch.
 */
object ProductionPath {

    const val CALL_CHAIN =
        "MainActivity.beginAutoPlaySetup" +
            " -> CaptureService/MediaProjection" +
            " -> FloatingBubbleService.ensureLoopRunning" +
            " -> ProductionCycleContext" +
            " -> AutoPlayController.runCycleIfActive" +
            " -> InputLoopController.runAnalyzeAndMaybeInput" +
            " -> AutomaticInputEngine.tryExecute" +
            " -> AccessibilityGestureExecutor" +
            " -> MatchMastersAccessibilityService.dispatchGesture" +
            " -> VerificationPolicy on a new fresh frame"

    enum class ExecutorRole {
        PRODUCTION_ACCESSIBILITY,
        SIMULATION_RECORDING,
        SHELL,
        UNINSTALLED,
        OTHER,
    }

    fun role(executor: InputGestureExecutor): ExecutorRole = when (executor) {
        is AccessibilityGestureExecutor -> ExecutorRole.PRODUCTION_ACCESSIBILITY
        is RecordingInputGestureExecutor -> ExecutorRole.SIMULATION_RECORDING
        is ShellInputGestureExecutor -> ExecutorRole.SHELL
        is UninstalledGestureExecutor -> ExecutorRole.UNINSTALLED
        else -> ExecutorRole.OTHER
    }

    fun isProductionExecutor(executor: InputGestureExecutor): Boolean =
        role(executor) == ExecutorRole.PRODUCTION_ACCESSIBILITY

    fun isSimulationExecutor(executor: InputGestureExecutor): Boolean =
        role(executor) == ExecutorRole.SIMULATION_RECORDING

    /**
     * Throws if [executor] is not the accessibility channel.
     * Called when the production session is composed.
     */
    fun requireProductionExecutor(executor: InputGestureExecutor) {
        require(isProductionExecutor(executor)) {
            "production path refuses ${role(executor)} (${executor.javaClass.simpleName}). " +
                "RecordingInputGestureExecutor and ShellInputGestureExecutor are not production."
        }
    }

    /**
     * Null context would fall through to evaluateGate defaults
     * (a11y connected, capture on, fresh frame). Simulated contexts are the JVM harness.
     * Both are refused before any gesture is built.
     */
    fun productionReadinessRefusal(context: RuntimeCycleContext?): String? {
        if (context == null) {
            return "HOLD — production path missing RuntimeCycleContext (refusing default readiness)"
        }
        if (context.simulated) {
            return "HOLD — production path refuses simulated readiness"
        }
        return null
    }
}

/**
 * The only executor factory the production session may call.
 */
object ProductionInstall {
    fun accessibilityExecutor(
        serviceProvider: () -> AccessibilityGestureChannel? =
            { MatchMastersAccessibilityService.instanceOrNull() },
    ): AccessibilityGestureExecutor {
        val exec = AccessibilityGestureExecutor(serviceProvider)
        ProductionPath.requireProductionExecutor(exec)
        return exec
    }
}

/**
 * Builds the [RuntimeCycleContext] the bubble loop passes in.
 * [simulated] is hard-coded false. Fields come from the caller’s measurements.
 */
object ProductionCycleContext {
    fun fromLoopObservation(
        a11yConnected: Boolean,
        captureManagerPresent: Boolean,
        hasFrame: Boolean,
        frameAgeMs: Long,
        frameSequenceDecision: FrameSequenceGate.Decision?,
        frameTimestampMs: Long,
        frameWidth: Int,
        frameHeight: Int,
    ): RuntimeCycleContext {
        check(!hasFrame || (frameWidth > 0 && frameHeight > 0)) {
            "production frame must carry a positive size"
        }
        return RuntimeCycleContext(
            a11yConnected = a11yConnected,
            captureOn = captureManagerPresent,
            hasFrame = hasFrame,
            frameAgeMs = frameAgeMs,
            frameSequenceDecision = frameSequenceDecision,
            // Gesture bounds are the capture bitmap pixels. Same numbers are
            // stored as the frame size so the mapping stays identity.
            // Equality with the accessibility display is device-dependent.
            screenWidth = frameWidth,
            screenHeight = frameHeight,
            frameTimestampMs = frameTimestampMs,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            simulated = false,
        )
    }
}

/**
 * Frame-pixel cell centers are sent to dispatchGesture unchanged.
 *
 * Identity is allowed only when the supplied frame size and the supplied
 * screen size are the same positive size, or when a separate frame size was
 * not provided (legacy callers that already put the bitmap size in screenWidth).
 * A size mismatch is refused — this code does not guess a scale.
 *
 * Whether those pixels are the accessibility screen is device-dependent
 * (rotation, cutout, OEM scale, single-app vs entire-display capture).
 */
object FrameScreenCoordinatePolicy {
    enum class Mapping { IDENTITY_FRAME_PIXELS, REFUSED }

    data class Assessment(
        val mapping: Mapping,
        val reason: String,
        /** True when a phone is still required to prove the pixels are the touch space. */
        val deviceDependent: Boolean,
    )

    fun assess(
        frameWidth: Int,
        frameHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Assessment {
        if (screenWidth <= 0 || screenHeight <= 0) {
            return Assessment(
                mapping = Mapping.REFUSED,
                reason = "screen bounds unknown (${screenWidth}x$screenHeight)",
                deviceDependent = false,
            )
        }
        if (frameWidth <= 0 || frameHeight <= 0) {
            return Assessment(
                mapping = Mapping.IDENTITY_FRAME_PIXELS,
                reason = "frame size not supplied separately; gesture bounds are " +
                    "${screenWidth}x$screenHeight capture pixels (no dp offset in code)",
                deviceDependent = true,
            )
        }
        if (frameWidth == screenWidth && frameHeight == screenHeight) {
            return Assessment(
                mapping = Mapping.IDENTITY_FRAME_PIXELS,
                reason = "1:1 frame px == supplied screen px " +
                    "(${frameWidth}x$frameHeight); no dp or status-bar offset in code",
                deviceDependent = true,
            )
        }
        return Assessment(
            mapping = Mapping.REFUSED,
            reason = "frame/screen size mismatch frame=${frameWidth}x$frameHeight " +
                "screen=${screenWidth}x$screenHeight (refusing to scale)",
            deviceDependent = true,
        )
    }
}
