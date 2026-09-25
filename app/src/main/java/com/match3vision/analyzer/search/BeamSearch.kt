package com.match3vision.analyzer.search

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.evaluation.MoveEvaluation
import com.match3vision.analyzer.evaluation.MoveEvaluator
import com.match3vision.analyzer.moves.FutureBoardGenerator
import com.match3vision.analyzer.moves.LegalMoveGenerator
import com.match3vision.analyzer.moves.Move
import com.match3vision.analyzer.vision.SpecialType

data class SearchNode(
    val board: Board,
    val path: List<Move>,
    val score: Float,
    val depth: Int,
    val uncertain: Boolean,
)

data class SearchTree(
    val rootHash: Long,
    val nodesExpanded: Int,
    val topLeaves: List<SearchNode>,
)

class SearchPruner(private val confidenceThreshold: Float = 0.4f) {
    fun keep(node: SearchNode, eval: MoveEvaluation): Boolean {
        if (eval.confidence < confidenceThreshold && eval.uncertain) return false
        if (eval.totalScore == Float.NEGATIVE_INFINITY) return false
        return true
    }
}

class FutureStateEvaluator {
    fun leafScore(board: Board, path: List<Move>): Float {
        if (path.isEmpty()) return 0f
        var specials = 0
        board.forEachTile { if (it.special != SpecialType.NONE) specials++ }
        return board.meanConfidence() * 20f + specials * 5f - board.unknownCount() * 15f
    }
}

class BeamSearch(
    private val maxDepth: Int = 2,
    private val beamWidth: Int = 5,
    private val timeBudgetMs: Long = 50L,
    private val confidenceThreshold: Float = 0.4f,
    private val topN: Int = 5,
    private val generator: LegalMoveGenerator = LegalMoveGenerator(),
    private val evaluator: MoveEvaluator = MoveEvaluator(),
    private val futureBoards: FutureBoardGenerator = FutureBoardGenerator(),
    private val pruner: SearchPruner = SearchPruner(confidenceThreshold),
    private val futureStateEvaluator: FutureStateEvaluator = FutureStateEvaluator(),
) {
    data class Config(
        val maxDepth: Int = 2,
        val beamWidth: Int = 5,
        val timeBudgetMs: Long = 50L,
        val confidenceThreshold: Float = 0.4f,
        val topN: Int = 5,
    )

    fun search(root: Board, config: Config = Config(maxDepth, beamWidth, timeBudgetMs, confidenceThreshold, topN)): SearchTree {
        val start = System.nanoTime()
        fun timedOut() = (System.nanoTime() - start) / 1_000_000L >= config.timeBudgetMs

        var beam = listOf(SearchNode(root, emptyList(), 0f, 0, root.unknownCount() > 0))
        var expanded = 0
        var depth = 0
        while (depth < config.maxDepth && !timedOut()) {
            val candidates = mutableListOf<SearchNode>()
            for (node in beam) {
                if (timedOut()) break
                for (move in generator.generate(node.board)) {
                    if (timedOut()) break
                    val eval = evaluator.evaluate(node.board, move)
                    expanded++
                    if (!pruner.keep(node, eval)) continue
                    val next = futureBoards.afterMove(node.board, move)
                    val path = node.path + move
                    val leafBonus = if (depth + 1 >= config.maxDepth) futureStateEvaluator.leafScore(next, path) else 0f
                    candidates += SearchNode(
                        board = next,
                        path = path,
                        score = node.score + eval.expectedValue + leafBonus,
                        depth = depth + 1,
                        uncertain = node.uncertain || eval.uncertain,
                    )
                }
            }
            if (candidates.isEmpty()) break
            beam = candidates.sortedByDescending { it.score }.take(config.beamWidth)
            depth++
        }
        return SearchTree(root.contentHash(), expanded, beam.sortedByDescending { it.score }.take(config.topN))
    }
}
