package com.match3vision.analyzer.orchestration

import com.match3vision.analyzer.ai.DecisionEngine
import com.match3vision.analyzer.board.GameState
import com.match3vision.analyzer.safety.SafetyGate
import com.match3vision.analyzer.vision.VisionResult

data class AnalysisUiSnapshot(
    val decisionBlocked: Boolean,
    val holdReason: String?,
    val topMovesLines: List<String>,
    val whyText: String,
    val confidenceText: String,
    val riskText: String,
    val expectedValueText: String,
    val gameStateText: String,
)

/** Vision → Board → Safety → Decision (display only). */
class AnalysisOrchestrator(
    private val decisionEngine: DecisionEngine = DecisionEngine(),
    private val safetyGate: SafetyGate = SafetyGate(),
) {
    fun analyzeVisionResult(
        vision: VisionResult,
        timestampMs: Long = System.currentTimeMillis(),
        uiLabels: List<String> = emptyList(),
    ): AnalysisUiSnapshot {
        val state = GameState.fromVisionResult(vision, timestampMs)
        val safety = safetyGate.check(state, timestampMs)
        if (!safety.allowDecision) {
            return AnalysisUiSnapshot(
                decisionBlocked = true,
                holdReason = safety.reason,
                topMovesLines = emptyList(),
                whyText = safety.reason,
                confidenceText = "board=${"%.2f".format(state.vision.boardConfidence)}",
                riskText = "HOLD",
                expectedValueText = "—",
                gameStateText = "unk=${state.vision.unknownCount} hash=${state.boardHash}",
            )
        }
        val decision = decisionEngine.decide(state, uiLabels)
        if (decision.blocked) {
            return AnalysisUiSnapshot(
                decisionBlocked = true,
                holdReason = decision.holdReason,
                topMovesLines = emptyList(),
                whyText = decision.whyLines.joinToString("\n"),
                confidenceText = "gate HOLD",
                riskText = "blocked",
                expectedValueText = "—",
                gameStateText = "mode=${decision.gameMode}",
            )
        }
        val top = decision.topMoves
        return AnalysisUiSnapshot(
            decisionBlocked = false,
            holdReason = null,
            topMovesLines = top.mapIndexed { i, e ->
                "#${i + 1} ${e.move} EV=${"%.1f".format(e.expectedValue)} conf=${"%.2f".format(e.confidence)}"
            },
            whyText = decision.whyLines.joinToString("\n"),
            confidenceText = top.firstOrNull()?.let { "%.2f".format(it.confidence) } ?: "—",
            riskText = top.firstOrNull()?.let { "%.1f".format(it.riskPenalty) } ?: "—",
            expectedValueText = top.firstOrNull()?.let { "%.1f".format(it.expectedValue) } ?: "—",
            gameStateText = "mode=${decision.gameMode} strategy=${decision.strategy} moves=${top.size}",
        )
    }
}
