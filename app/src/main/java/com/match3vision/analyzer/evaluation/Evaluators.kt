package com.match3vision.analyzer.evaluation

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.moves.Move
import com.match3vision.analyzer.moves.MoveSimulator
import com.match3vision.analyzer.vision.SpecialType
import kotlin.math.max

class ScoreCalculator {
    fun matchPoints(clearedCells: Int): Float = clearedCells * 10f
}

class StarCalculator {
    fun stars(board: Board, clearedHint: Int): Float {
        var starTiles = 0
        board.forEachTile { if (it.starValue > 0) starTiles += it.starValue }
        return starTiles * 5f + clearedHint * 0.5f
    }
}

class BoosterCalculator {
    fun score(board: Board): Float {
        var n = 0
        board.forEachTile { if (it.special != SpecialType.NONE) n++ }
        return n * 8f
    }
}

class CascadeEvaluator {
    fun score(steps: Int, cleared: Int): Float =
        steps * 12f + max(0, cleared - 3) * 4f
}

class SpecialEvaluator {
    fun score(boardBefore: Board, boardAfter: Board): Float {
        var before = 0
        var after = 0
        boardBefore.forEachTile { if (it.special != SpecialType.NONE) before++ }
        boardAfter.forEachTile { if (it.special != SpecialType.NONE) after++ }
        return (after - before) * 15f + before * 3f
    }
}

class FutureBoardEvaluator {
    fun score(boardAfter: Board): Float {
        val unk = boardAfter.unknownCount()
        var specials = 0
        boardAfter.forEachTile { if (it.special != SpecialType.NONE) specials++ }
        return specials * 5f - unk * 20f + boardAfter.meanConfidence() * 10f
    }
}

class RiskEvaluator {
    fun penalty(uncertain: Boolean, unknownCount: Int, confidencePenalty: Float): Float {
        var p = confidencePenalty * 40f
        if (uncertain) p += 25f
        p += unknownCount * 8f
        return p
    }
}

class ExpectedValueCalculator {
    fun ev(totalPositive: Float, riskPenalty: Float, confidence: Float): Float {
        val conf = confidence.coerceIn(0.05f, 1f)
        return (totalPositive - riskPenalty) * conf
    }
}

class WhyEngine {
    fun explain(eval: MoveEvaluation): List<String> {
        val lines = mutableListOf<String>()
        lines += "Move ${eval.move}"
        if (eval.matchScore > 0) lines += "match +${"%.1f".format(eval.matchScore)}"
        if (eval.cascadeScore > 0) lines += "cascade +${"%.1f".format(eval.cascadeScore)}"
        if (eval.specialScore != 0f) lines += "special ${"%.1f".format(eval.specialScore)}"
        if (eval.starScore > 0) lines += "stars +${"%.1f".format(eval.starScore)}"
        if (eval.riskPenalty > 0) lines += "risk -${"%.1f".format(eval.riskPenalty)}"
        lines += "EV ${"%.1f".format(eval.expectedValue)} conf ${"%.2f".format(eval.confidence)}"
        if (eval.uncertain) lines += "UNCERTAIN (UNKNOWN/refill)"
        return lines
    }
}

class MoveEvaluator(
    private val simulator: MoveSimulator = MoveSimulator(),
    private val scoreCalculator: ScoreCalculator = ScoreCalculator(),
    private val starCalculator: StarCalculator = StarCalculator(),
    private val boosterCalculator: BoosterCalculator = BoosterCalculator(),
    private val cascadeEvaluator: CascadeEvaluator = CascadeEvaluator(),
    private val specialEvaluator: SpecialEvaluator = SpecialEvaluator(),
    private val futureBoardEvaluator: FutureBoardEvaluator = FutureBoardEvaluator(),
    private val riskEvaluator: RiskEvaluator = RiskEvaluator(),
    private val expectedValueCalculator: ExpectedValueCalculator = ExpectedValueCalculator(),
    private val whyEngine: WhyEngine = WhyEngine(),
) {
    fun evaluate(board: Board, move: Move): MoveEvaluation {
        val sim = simulator.simulate(board, move)
        if (!sim.legal) {
            return MoveEvaluation(
                move = move,
                totalScore = Float.NEGATIVE_INFINITY,
                matchScore = 0f, cascadeScore = 0f, specialScore = 0f,
                starScore = 0f, boosterScore = 0f, futureScore = 0f,
                riskPenalty = 100f, expectedValue = Float.NEGATIVE_INFINITY,
                confidence = 0f, uncertain = true, reasons = listOf("illegal move"),
            )
        }
        val matchScore = scoreCalculator.matchPoints(sim.clearedCells)
        val cascadeScore = cascadeEvaluator.score(sim.cascadeSteps, sim.clearedCells)
        val specialScore = specialEvaluator.score(board, sim.boardAfter)
        val starScore = starCalculator.stars(board, sim.clearedCells)
        val boosterScore = boosterCalculator.score(board)
        val futureScore = futureBoardEvaluator.score(sim.boardAfter)
        val risk = riskEvaluator.penalty(sim.uncertain, board.unknownCount(), sim.confidencePenalty)
        val positive = matchScore + cascadeScore + specialScore + starScore + boosterScore * 0.25f + futureScore
        val conf = (board.meanConfidence() * (1f - sim.confidencePenalty)).coerceIn(0f, 1f)
        val ev = expectedValueCalculator.ev(positive, risk, conf)
        val base = MoveEvaluation(
            move = move, totalScore = ev, matchScore = matchScore, cascadeScore = cascadeScore,
            specialScore = specialScore, starScore = starScore, boosterScore = boosterScore,
            futureScore = futureScore, riskPenalty = risk, expectedValue = ev,
            confidence = conf, uncertain = sim.uncertain, reasons = emptyList(),
        )
        return base.copy(reasons = whyEngine.explain(base))
    }
}
