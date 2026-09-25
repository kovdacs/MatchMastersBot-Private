package com.match3vision.analyzer.board

import com.match3vision.analyzer.vision.ValidationResult
import com.match3vision.analyzer.vision.VisionResult

enum class TurnSide { PLAYER, OPPONENT, UNKNOWN }

data class PlayerState(
    val score: Int? = null,
    val stars: Int? = null,
    val movesRemaining: Int? = null,
    val observable: Boolean = false,
) {
    companion object { fun unknown() = PlayerState(observable = false) }
}

data class OpponentState(
    val score: Int? = null,
    val stars: Int? = null,
    val observable: Boolean = false,
) {
    companion object { fun unknown() = OpponentState(observable = false) }
}

data class VisionState(
    val unknownCount: Int = 0,
    val boardConfidence: Float = 0f,
    val gridConfidence: Float = 0f,
    val gatePass: Boolean = false,
    val holdReason: String? = null,
)

data class GameState(
    val board: Board,
    val player: PlayerState = PlayerState.unknown(),
    val opponent: OpponentState = OpponentState.unknown(),
    val vision: VisionState = VisionState(),
    val turn: TurnSide = TurnSide.UNKNOWN,
    val frameTimestampMs: Long = 0L,
    val boardHash: Long = board.contentHash(),
) {
    companion object {
        fun fromVisionResult(result: VisionResult, timestampMs: Long = 0L): GameState {
            val board = Board.fromVision(result.board)
            val gatePass = result.validation.isPass
            val hold = (result.validation as? ValidationResult.Hold)?.reason
            return GameState(
                board = board,
                vision = VisionState(
                    unknownCount = result.unknownCount,
                    boardConfidence = result.boardConfidence,
                    gridConfidence = result.gridConfidence,
                    gatePass = gatePass,
                    holdReason = hold,
                ),
                frameTimestampMs = timestampMs,
                boardHash = board.contentHash(),
            )
        }
    }
}

class BoardHistory(private val capacity: Int = 32) {
    private val hashes = ArrayDeque<Long>()
    private val timestamps = ArrayDeque<Long>()

    fun push(board: Board, timestampMs: Long) {
        hashes.addLast(board.contentHash())
        timestamps.addLast(timestampMs)
        while (hashes.size > capacity) {
            hashes.removeFirst()
            timestamps.removeFirst()
        }
    }

    fun lastHash(): Long? = hashes.lastOrNull()
    fun isDuplicateOfLast(board: Board): Boolean = hashes.lastOrNull() == board.contentHash()
    fun size(): Int = hashes.size
    fun clear() { hashes.clear(); timestamps.clear() }
}
