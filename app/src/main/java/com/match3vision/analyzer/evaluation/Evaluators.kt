package com.match3vision.analyzer.evaluation

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.moves.Move
import com.match3vision.analyzer.moves.MoveSimulator
import com.match3vision.analyzer.vision.SpecialType
import kotlin.math.max

class ScoreCalculator {
    fun matchPoints(clearedCells: Int, maxMatchSize: Int, concurrent: Int): Float {
        val sizeBonus = when {
            maxMatchSize >= 5 -> 25f
            maxMatchSize == 4 -> 12f
            else -> 0f
        }
        val concurrentBonus = max(0, concurrent - 1) * 8f
        return clearedCells * 10f + sizeBonus + concurrentBonus
    }
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
    fun score(
        boardBefore: Board,
        boardAfter: Board,
        specialsCreated: Int,
        existingActivated: Boolean,
    ): Float {
        var before = 0
        var after = 0
        boardBefore.forEachTile { if (it.special != SpecialType.NONE) before++ }
        boardAfter.forEachTile { if (it.special != SpecialType.NONE) after++ }
        var s = (after - before) * 15f + before * 3f
        s += specialsCreated * 18f
        if (existingActivated) s += 20f
        return s
    }
}

class ExtraMoveEvaluator {
    /** Proxy: 5-match / special create / deep cascade often grants extra turn (ASSUMPTION). */
    fun score(extraMoveLikely: Boolean, maxMatchSize: Int, specialsCreated: Int): Float {
        if (!extraMoveLikely && maxMatchSize < 5 && specialsCreated == 0) return 0f
        var s = 0f
        if (maxMatchSize >= 5) s += 22f
        if (specialsCreated > 0) s += 10f
        if (extraMoveLikely) s += 8f
        return s
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

class BoardDeltaEvaluator {
    fun score(before: Board, after: Board): Float {
        val hashDelta = if (before.contentHash() != after.contentHash()) 1f else 0f
        return hashDelta * 2f + (before.unknownCount() - after.unknownCount()) * 3f
    }
}

class WhyEngine {
    fun explain(eval: MoveEvaluation): List<String> {
        val lines = mutableListOf<String>()
        lines += "Move ${eval.move}"
        if (eval.matchScore > 0) {
            lines += "match +${"%.1f".format(eval.matchScore)}" +
                " (n=${eval.matchCount} max=${eval.maxMatchSize} concurrent=${eval.concurrentMatches})"
        }
        if (eval.cascadeScore > 0) lines += "cascade +${"%.1f".format(eval.cascadeScore)}"
        if (eval.specialScore != 0f) lines += "special ${"%.1f".format(eval.specialScore)}"
        if (eval.extraMove > 0) lines += "extraMove +${"%.1f".format(eval.extraMove)}"
        if (eval.starScore > 0) lines += "stars +${"%.1f".format(eval.starScore)}"
        if (eval.boosterScore > 0) lines += "booster +${"%.1f".format(eval.boosterScore)}"
        if (eval.opponent != 0f) lines += "opponent ${"%.1f".format(eval.opponent)}"
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
    private val extraMoveEvaluator: ExtraMoveEvaluator = ExtraMoveEvaluator(),
    private val futureBoardEvaluator: FutureBoardEvaluator = FutureBoardEvaluator(),
    private val riskEvaluator: RiskEvaluator = RiskEvaluator(),
    private val expectedValueCalculator: ExpectedValueCalculator = ExpectedValueCalculator(),
    private val boardDeltaEvaluator: BoardDeltaEvaluator = BoardDeltaEvaluator(),
    private val whyEngine: WhyEngine = WhyEngine(),
) {
    fun evaluate(board: Board, move: Move, opponentScore: Float = 0f): MoveEvaluation {
        val sim = simulator.simulate(board, move)
        if (!sim.legal) {
            return MoveEvaluation(
                move = move,
                totalScore = Float.NEGATIVE_INFINITY,
                matchScore = 0f, cascadeScore = 0f, specialScore = 0f,
                starScore = 0f, boosterScore = 0f, futureScore = 0f,
                riskPenalty = 100f, expectedValue = Float.NEGATIVE_INFINITY,
                confidence = 0f, uncertain = true, reasons = listOf("illegal move"),
                extraMove = 0f, opponent = opponentScore,
            )
        }
        val matchScore = scoreCalculator.matchPoints(
            sim.clearedCells, sim.maxMatchSize, sim.concurrentMatches,
        )
        val cascadeScore = cascadeEvaluator.score(sim.cascadeSteps, sim.clearedCells)
        val specialScore = specialEvaluator.score(
            board, sim.boardAfter, sim.specialsCreated, sim.existingSpecialActivated,
        )
        val starScore = starCalculator.stars(board, sim.clearedCells)
        val boosterScore = boosterCalculator.score(board)
        val futureScore = futureBoardEvaluator.score(sim.boardAfter) +
            boardDeltaEvaluator.score(board, sim.boardAfter)
        val extraMove = extraMoveEvaluator.score(
            sim.extraMoveLikely, sim.maxMatchSize, sim.specialsCreated,
        )
        val risk = riskEvaluator.penalty(sim.uncertain, board.unknownCount(), sim.confidencePenalty)
        val positive = matchScore + cascadeScore + specialScore + starScore +
            boosterScore * 0.25f + futureScore + extraMove - opponentScore
        val conf = (board.meanConfidence() * (1f - sim.confidencePenalty)).coerceIn(0f, 1f)
        val ev = expectedValueCalculator.ev(positive, risk, conf)
        val base = MoveEvaluation(
            move = move,
            totalScore = ev,
            matchScore = matchScore,
            cascadeScore = cascadeScore,
            specialScore = specialScore,
            starScore = starScore,
            boosterScore = boosterScore,
            futureScore = futureScore,
            riskPenalty = risk,
            expectedValue = ev,
            confidence = conf,
            uncertain = sim.uncertain,
            reasons = emptyList(),
            extraMove = extraMove,
            opponent = opponentScore,
            matchCount = sim.matchCount,
            maxMatchSize = sim.maxMatchSize,
            concurrentMatches = sim.concurrentMatches,
            specialCreated = sim.specialsCreated > 0,
            existingSpecialActivated = sim.existingSpecialActivated,
        )
        return base.copy(reasons = whyEngine.explain(base))
    }
}
