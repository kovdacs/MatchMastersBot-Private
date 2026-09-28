package com.match3vision.analyzer.evaluation

import com.match3vision.analyzer.moves.Move

/**
 * Ranked move candidate for display only (analyzer never actuates).
 *
 * V1 contract aliases: [score], [stars], [booster], [cascade], [special],
 * [extraMove], [future], [opponent], [risk], [EV], [confidence], [WHY].
 */
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
    val extraMove: Float = 0f,
    val opponent: Float = 0f,
    val matchCount: Int = 0,
    val maxMatchSize: Int = 0,
    val concurrentMatches: Int = 0,
    val specialCreated: Boolean = false,
    val existingSpecialActivated: Boolean = false,
) {
    val why: String get() = reasons.joinToString("; ")

    /** V1 field aliases (ARCHITECTURE / MOVE ANALYSIS ENGINE V1). */
    val score: Float get() = totalScore
    val stars: Float get() = starScore
    val booster: Float get() = boosterScore
    val cascade: Float get() = cascadeScore
    val special: Float get() = specialScore
    val future: Float get() = futureScore
    val risk: Float get() = riskPenalty
    val EV: Float get() = expectedValue
    val WHY: String get() = why
}
