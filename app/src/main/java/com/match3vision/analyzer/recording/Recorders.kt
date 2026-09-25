package com.match3vision.analyzer.recording

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.evaluation.MoveEvaluation
import com.match3vision.analyzer.vision.VisionResult

data class FrameRecord(val timestampMs: Long, val width: Int, val height: Int, val boardHash: Long)
data class BoardRecord(val timestampMs: Long, val hash: Long, val unknownCount: Int, val json: String)
data class DecisionRecord(val timestampMs: Long, val blocked: Boolean, val topMovesJson: String, val why: String)
data class MatchRecord(val matchId: String, val frames: List<FrameRecord>, val decisions: List<DecisionRecord>)

class FrameRecorder {
    private val frames = mutableListOf<FrameRecord>()
    fun record(timestampMs: Long, width: Int, height: Int, boardHash: Long) {
        frames += FrameRecord(timestampMs, width, height, boardHash)
    }
    fun all(): List<FrameRecord> = frames.toList()
    fun exportJson(): String = frames.joinToString(prefix = "[", postfix = "]") {
        """{"ts":${it.timestampMs},"w":${it.width},"h":${it.height},"hash":${it.boardHash}}"""
    }
    fun clear() = frames.clear()
}

class BoardRecorder {
    private val items = mutableListOf<BoardRecord>()
    fun record(timestampMs: Long, board: Board, unknownCount: Int, json: String) {
        items += BoardRecord(timestampMs, board.contentHash(), unknownCount, json)
    }
    fun exportJson(): String = items.joinToString(prefix = "[", postfix = "]") {
        """{"ts":${it.timestampMs},"hash":${it.hash},"unk":${it.unknownCount}}"""
    }
    fun clear() = items.clear()
}

class DecisionRecorder {
    private val items = mutableListOf<DecisionRecord>()
    fun record(timestampMs: Long, blocked: Boolean, top: List<MoveEvaluation>, why: String) {
        val topJson = top.joinToString(prefix = "[", postfix = "]") {
            """{"move":"${it.move}","ev":${it.expectedValue}}"""
        }
        items += DecisionRecord(timestampMs, blocked, topJson, why)
    }
    fun exportJson(): String = items.joinToString(prefix = "[", postfix = "]") {
        """{"ts":${it.timestampMs},"blocked":${it.blocked},"top":${it.topMovesJson}}"""
    }
    fun clear() = items.clear()
}

class MatchRecorder(
    private val frameRecorder: FrameRecorder = FrameRecorder(),
    private val boardRecorder: BoardRecorder = BoardRecorder(),
    private val decisionRecorder: DecisionRecorder = DecisionRecorder(),
) {
    fun frames() = frameRecorder
    fun boards() = boardRecorder
    fun decisions() = decisionRecorder
    fun exportJson(matchId: String): String =
        """{"matchId":"$matchId","frames":${frameRecorder.exportJson()},"decisions":${decisionRecorder.exportJson()}}"""
}

class ReplayEngine {
    fun replayDecisionJson(json: String): String = json // passthrough for analyzer export
}
