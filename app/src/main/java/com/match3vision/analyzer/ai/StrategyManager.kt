package com.match3vision.analyzer.ai

import com.match3vision.analyzer.evaluation.MoveEvaluation

enum class StrategyKind {
    BALANCED, STAR, BOOSTER, COMBO, DEFENSIVE, COMEBACK, ENDGAME,
}

class StrategyManager(var active: StrategyKind = StrategyKind.BALANCED) {
    fun weight(eval: MoveEvaluation): Float {
        val base = eval.expectedValue
        return when (active) {
            StrategyKind.BALANCED -> base
            StrategyKind.STAR -> base + eval.starScore * 0.8f
            StrategyKind.BOOSTER -> base + eval.boosterScore * 0.6f
            StrategyKind.COMBO -> base + eval.cascadeScore * 0.7f + eval.specialScore * 0.5f
            StrategyKind.DEFENSIVE -> base - eval.riskPenalty * 0.5f
            StrategyKind.COMEBACK -> base + eval.matchScore * 0.5f + eval.cascadeScore * 0.4f
            StrategyKind.ENDGAME -> base + eval.futureScore * 0.3f - eval.riskPenalty * 0.3f
        }
    }
}

class MoveRanker(private val strategyManager: StrategyManager = StrategyManager()) {
    fun rank(evals: List<MoveEvaluation>, topN: Int = 5): List<MoveEvaluation> =
        evals.sortedByDescending { strategyManager.weight(it) }.take(topN)
}
