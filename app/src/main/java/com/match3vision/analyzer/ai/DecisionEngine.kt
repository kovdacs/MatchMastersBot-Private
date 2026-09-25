package com.match3vision.analyzer.ai

import com.match3vision.analyzer.board.GameState
import com.match3vision.analyzer.booster.BoosterDetector
import com.match3vision.analyzer.booster.BoosterEvaluator
import com.match3vision.analyzer.booster.PerkDetector
import com.match3vision.analyzer.booster.PerkEvaluator
import com.match3vision.analyzer.evaluation.MoveEvaluation
import com.match3vision.analyzer.evaluation.MoveEvaluator
import com.match3vision.analyzer.evaluation.WhyEngine
import com.match3vision.analyzer.gamemode.GameMode
import com.match3vision.analyzer.gamemode.GameModeDetector
import com.match3vision.analyzer.moves.LegalMoveGenerator
import com.match3vision.analyzer.opponent.OpponentResponseModel
import com.match3vision.analyzer.opponent.OpponentThreatEvaluator
import com.match3vision.analyzer.search.BeamSearch

/**
 * Decision AI — TOP-N [MoveEvaluation] for display only.
 * NEVER executes input. Vision/safety HOLD → blocked.
 */
class DecisionEngine(
    private val moveGenerator: LegalMoveGenerator = LegalMoveGenerator(),
    private val moveEvaluator: MoveEvaluator = MoveEvaluator(),
    private val moveRanker: MoveRanker = MoveRanker(),
    private val strategyManager: StrategyManager = StrategyManager(),
    private val whyEngine: WhyEngine = WhyEngine(),
    private val beamSearch: BeamSearch = BeamSearch(
        maxDepth = 2,
        beamWidth = 4,
        timeBudgetMs = 40L,
        topN = 5,
    ),
    private val gameModeDetector: GameModeDetector = GameModeDetector(),
    private val threatEvaluator: OpponentThreatEvaluator = OpponentThreatEvaluator(),
    private val responseModel: OpponentResponseModel = OpponentResponseModel(),
    private val boosterDetector: BoosterDetector = BoosterDetector(),
    private val boosterEvaluator: BoosterEvaluator = BoosterEvaluator(),
    private val perkDetector: PerkDetector = PerkDetector(),
    private val perkEvaluator: PerkEvaluator = PerkEvaluator(),
) {
    data class DecisionResult(
        val blocked: Boolean,
        val holdReason: String?,
        val topMoves: List<MoveEvaluation>,
        val strategy: StrategyKind,
        val gameMode: GameMode,
        val whyLines: List<String>,
    )

    fun decide(
        state: GameState,
        uiLabels: List<String> = emptyList(),
        useLookahead: Boolean = true,
        topN: Int = 5,
    ): DecisionResult {
        if (!state.vision.gatePass) {
            return DecisionResult(
                blocked = true,
                holdReason = state.vision.holdReason
                    ?: "HOLD — Decision AI blocked (vision gate)",
                topMoves = emptyList(),
                strategy = strategyManager.active,
                gameMode = GameMode.UNKNOWN_GAME_MODE,
                whyLines = listOf("Vision gate HOLD — no actionable moves"),
            )
        }

        val mode = gameModeDetector.detect(uiLabels)
        val threat = threatEvaluator.evaluate(state.opponent, state.board)
        val prior = responseModel.prior(threat)
        val defensiveBias = responseModel.suggestedDefensiveBias(prior)
        if (defensiveBias > 0f) strategyManager.active = StrategyKind.DEFENSIVE

        val (boosterScore, _) = boosterEvaluator.evaluate(
            boosterDetector.detectFromScreenLabels(uiLabels),
        )
        val (perkScore, _) = perkEvaluator.evaluate(
            perkDetector.detectFromScreenLabels(uiLabels),
        )

        val board = state.board
        var evals = moveGenerator.generate(board)
            .map { moveEvaluator.evaluate(board, it) }
            .map {
                it.copy(
                    expectedValue = it.expectedValue + boosterScore * 0.1f + perkScore * 0.1f -
                        defensiveBias * 10f,
                )
            }

        if (useLookahead && evals.isNotEmpty()) {
            val tree = beamSearch.search(board)
            evals = evals.map { e ->
                val bonus = tree.topLeaves
                    .mapNotNull { leaf -> leaf.path.firstOrNull()?.let { m -> m to leaf.score } }
                    .filter { it.first.normalized() == e.move.normalized() }
                    .maxOfOrNull { it.second } ?: 0f
                e.copy(expectedValue = e.expectedValue + 0.15f * bonus)
            }
        }

        val ranked = moveRanker.rank(evals, topN)
        val why = ranked.firstOrNull()?.let { whyEngine.explain(it) } ?: listOf("No legal moves")
        return DecisionResult(false, null, ranked, strategyManager.active, mode.mode, why)
    }
}
