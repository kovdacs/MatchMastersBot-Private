package com.match3vision.analyzer.evaluation

import com.match3vision.analyzer.moves.Move

data class MoveEvaluation(
    val move: Move,
    val totalScore: Float,
    val matchScore: Float,
    val cascadeScore: Float,
    val specialScore: Float,
    val starScore: Float,
    val boosterScore: Float,
    val futureScore: Float,
    val riskPenalty: Float,
    val expectedValue: Float,
    val confidence: Float,
    val uncertain: Boolean,
    val reasons: List<String>,
) {
    val why: String get() = reasons.joinToString("; ")
}
