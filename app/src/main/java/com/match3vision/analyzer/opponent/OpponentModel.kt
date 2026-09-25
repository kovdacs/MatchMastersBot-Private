package com.match3vision.analyzer.opponent

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.board.BoardHistory
import com.match3vision.analyzer.board.OpponentState
import com.match3vision.analyzer.vision.SpecialType

/** Screen-observable facts only. Missing HUD → UNKNOWN. */
class OpponentStateTracker {
    private var last: OpponentState = OpponentState.unknown()
    fun updateObservable(score: Int?, stars: Int?): OpponentState {
        last = OpponentState(score = score, stars = stars, observable = score != null || stars != null)
        return last
    }
    fun current(): OpponentState = last
}

class OpponentMoveDetector(private val history: BoardHistory = BoardHistory()) {
    data class Detection(val changed: Boolean, val unknown: Boolean, val note: String)
    fun observe(board: Board, timestampMs: Long): Detection {
        if (history.size() == 0) {
            history.push(board, timestampMs)
            return Detection(false, false, "first frame")
        }
        val dup = history.isDuplicateOfLast(board)
        history.push(board, timestampMs)
        return if (dup) Detection(false, false, "no change")
        else Detection(true, true, "board changed — actor UNKNOWN (not screen-labeled)")
    }
}

class OpponentThreatEvaluator {
    data class Threat(val level: Float, val unknown: Boolean, val reasons: List<String>)
    fun evaluate(opponent: OpponentState, board: Board): Threat {
        if (!opponent.observable) {
            return Threat(0f, true, listOf("opponent HUD not observable → UNKNOWN"))
        }
        var level = 0f
        val reasons = mutableListOf<String>()
        opponent.score?.let { level += (it / 1000f).coerceAtMost(5f); reasons += "score=$it" }
        opponent.stars?.let { level += it * 0.5f; reasons += "stars=$it" }
        var specials = 0
        board.forEachTile { if (it.special != SpecialType.NONE) specials++ }
        level += specials * 0.2f
        return Threat(level, false, reasons)
    }
}

class OpponentResponseModel {
    data class ResponsePrior(
        val likelyAggressive: Boolean,
        val confidence: Float,
        val unknown: Boolean,
        val note: String,
    )
    fun prior(threat: OpponentThreatEvaluator.Threat): ResponsePrior {
        if (threat.unknown) {
            return ResponsePrior(false, 0f, true, "UNKNOWN — no observable opponent policy")
        }
        return ResponsePrior(
            likelyAggressive = threat.level >= 2f,
            confidence = (threat.level / 10f).coerceAtMost(0.3f),
            unknown = false,
            note = "weak prior from observable HUD/board only",
        )
    }
    fun suggestedDefensiveBias(prior: ResponsePrior): Float =
        if (prior.unknown) 0f else if (prior.likelyAggressive) 0.15f else 0f
}
