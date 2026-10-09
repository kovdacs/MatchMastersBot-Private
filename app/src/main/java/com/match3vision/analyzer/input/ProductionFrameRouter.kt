package com.match3vision.analyzer.input

import com.match3vision.analyzer.capture.CaptureBufferGate
import com.match3vision.analyzer.vision.VisionResult

/**
 * The only production route from a capture buffer to MoveAnalysis and dispatch.
 *
 * [analyze] is VisionPipeline. [moveAnalysis] runs only after a copied buffer
 * is admitted. [AutoPlayController.runCycleIfActive] is the dispatch path.
 * A failed copy returns before either call, so a zero substitute cannot reach
 * a PASS, a move, or the production channel.
 */
object ProductionFrameRouter {
    data class Routed(
        val admitted: Boolean,
        val failureClass: String?,
        val reason: String,
        val vision: VisionResult?,
        val cycle: InputLoopController.CycleResult?,
    )

    fun route(
        copySucceeded: Boolean,
        width: Int,
        height: Int,
        bufferLength: Int,
        controller: AutoPlayController,
        context: RuntimeCycleContext?,
        analyze: () -> VisionResult,
        moveAnalysis: () -> Unit = {},
    ): Routed {
        val admission = CaptureBufferGate.analyzeIfAdmitted(
            copySucceeded = copySucceeded,
            width = width,
            height = height,
            bufferLength = bufferLength,
            analyze = analyze,
        )
        val vision = admission.value
        if (!admission.admitted || vision == null) {
            return Routed(
                admitted = false,
                failureClass = CaptureBufferGate.FAILURE_CLASS,
                reason = admission.reason,
                vision = null,
                cycle = null,
            )
        }
        moveAnalysis()
        return Routed(
            admitted = true,
            failureClass = null,
            reason = admission.reason,
            vision = vision,
            cycle = controller.runCycleIfActive(vision, context),
        )
    }
}
