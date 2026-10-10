package com.match3vision.analyzer.recording

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.evaluation.MoveEvaluation
import com.match3vision.analyzer.hud.Json
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
    fun exportJson(): String = Json.write(
        Json.Arr(frames.map {
            Json.Obj(
                linkedMapOf(
                    "ts" to Json.Num(it.timestampMs.toString()),
                    "w" to Json.Num(it.width.toString()),
                    "h" to Json.Num(it.height.toString()),
                    "hash" to Json.Str(it.boardHash.toString()),
                ),
            )
        }),
    )
    fun clear() = frames.clear()
}

class BoardRecorder {
    private val items = mutableListOf<BoardRecord>()
    fun record(timestampMs: Long, board: Board, unknownCount: Int, json: String) {
        items += BoardRecord(timestampMs, board.contentHash(), unknownCount, json)
    }
    fun exportJson(): String = Json.write(
        Json.Arr(items.map {
            Json.Obj(
                linkedMapOf(
                    "ts" to Json.Num(it.timestampMs.toString()),
                    "hash" to Json.Str(it.hash.toString()),
                    "unk" to Json.Num(it.unknownCount.toString()),
                    "board" to Json.Str(it.json),
                ),
            )
        }),
    )
    fun clear() = items.clear()
}

class DecisionRecorder {
    private val items = mutableListOf<DecisionRecord>()
    fun record(timestampMs: Long, blocked: Boolean, top: List<MoveEvaluation>, why: String) {
        val topJson = Json.write(
            Json.Arr(top.map {
                Json.Obj(
                    linkedMapOf(
                        "move" to Json.Str(it.move.toString()),
                        "ev" to Json.Num(it.expectedValue.toString()),
                    ),
                )
            }),
        )
        items += DecisionRecord(timestampMs, blocked, topJson, why)
    }
    fun exportJson(): String = Json.write(
        Json.Arr(items.map {
            val top = runCatching { Json.parse(it.topMovesJson) }.getOrElse { Json.Arr(emptyList()) }
            Json.Obj(
                linkedMapOf(
                    "ts" to Json.Num(it.timestampMs.toString()),
                    "blocked" to Json.Bool(it.blocked),
                    "top" to top,
                    "why" to Json.Str(it.why),
                ),
            )
        }),
    )
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
    fun exportJson(matchId: String): String {
        val frames = Json.parse(frameRecorder.exportJson())
        val boards = Json.parse(boardRecorder.exportJson())
        val decisions = Json.parse(decisionRecorder.exportJson())
        return Json.write(
            Json.Obj(
                linkedMapOf(
                    "matchId" to Json.Str(matchId),
                    "frames" to frames,
                    "boards" to boards,
                    "decisions" to decisions,
                ),
            ),
        )
    }
}

class ReplayEngine {
    fun replayDecisionJson(json: String): String = json // passthrough for analyzer export
}
