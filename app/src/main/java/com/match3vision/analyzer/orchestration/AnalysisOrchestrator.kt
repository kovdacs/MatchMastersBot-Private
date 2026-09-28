package com.match3vision.analyzer.orchestration

import com.match3vision.analyzer.board.GameState
import com.match3vision.analyzer.moves.MoveAnalysisEngine
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

/**
 * Vision → Board → Safety → MoveAnalysisEngine V1 (display only). Input via AutomaticInputEngine is separate and default DISABLED.
 * Never actuates taps/swipes. HOLD → no moves.
 */
class AnalysisOrchestrator(
    private val moveAnalysisEngine: MoveAnalysisEngine = MoveAnalysisEngine(),
    private val safetyGate: SafetyGate = SafetyGate(),
    private val enableMoveAnalysis: Boolean = true,
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
        if (!enableMoveAnalysis) {
            return AnalysisUiSnapshot(
                decisionBlocked = true,
                holdReason = MoveAnalysisEngine.HOLD_BLOCKED,
                topMovesLines = emptyList(),
                whyText = "Move analysis disabled",
                confidenceText = "gate PASS (analysis off)",
                riskText = "—",
                expectedValueText = "—",
                gameStateText = "moveAnalysis=off",
            )
        }
        // Optional V1 call after Vision PASS + safety — read-only ranking.
        @Suppress("UNUSED_VARIABLE")
        val ignoredLabels = uiLabels
        val analysis = moveAnalysisEngine.analyze(state)
        if (analysis.blocked) {
            return AnalysisUiSnapshot(
                decisionBlocked = true,
                holdReason = analysis.holdReason ?: MoveAnalysisEngine.HOLD_BLOCKED,
                topMovesLines = emptyList(),
                whyText = analysis.holdReason ?: MoveAnalysisEngine.HOLD_BLOCKED,
                confidenceText = "gate HOLD",
                riskText = "blocked",
                expectedValueText = "—",
                gameStateText = "MoveAnalysisEngine HOLD",
            )
        }
        val top = analysis.top5
        return AnalysisUiSnapshot(
            decisionBlocked = false,
            holdReason = null,
            topMovesLines = top.mapIndexed { i, e ->
                "#${i + 1} ${e.move} EV=${"%.1f".format(e.EV)} conf=${"%.2f".format(e.confidence)}" +
                    " why=${e.WHY.take(80)}"
            },
            whyText = top.firstOrNull()?.WHY ?: "No legal moves",
            confidenceText = top.firstOrNull()?.let { "%.2f".format(it.confidence) } ?: "—",
            riskText = top.firstOrNull()?.let { "%.1f".format(it.risk) } ?: "—",
            expectedValueText = top.firstOrNull()?.let { "%.1f".format(it.EV) } ?: "—",
            gameStateText = "MoveAnalysisEngine V1 moves=${top.size}",
        )
    }
}
