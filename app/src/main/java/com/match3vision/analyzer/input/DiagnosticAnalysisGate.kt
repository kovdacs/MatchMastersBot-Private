package com.match3vision.analyzer.input

/**
 * Splits diagnostic capture from a play cycle.
 *
 * Accessibility off still analyzes and exports when a frame is present.
 * It never asks [AutoPlayController.runCycleIfActive] to dispatch.
 */
object DiagnosticAnalysisGate {
    data class Decision(
        val analyzeAndExport: Boolean,
        val callRunCycle: Boolean,
        val reason: String,
    )

    fun decide(
        captureOn: Boolean,
        hasFrame: Boolean,
        analysisOnly: Boolean,
        a11yConnected: Boolean,
    ): Decision {
        if (!captureOn || !hasFrame) {
            return Decision(
                analyzeAndExport = false,
                callRunCycle = false,
                reason = "no frame — analysis not run",
            )
        }
        if (analysisOnly || !a11yConnected) {
            return Decision(
                analyzeAndExport = true,
                callRunCycle = false,
                reason = "diagnostic analysis only — no touch",
            )
        }
        return Decision(
            analyzeAndExport = true,
            callRunCycle = true,
            reason = "play cycle",
        )
    }
}
