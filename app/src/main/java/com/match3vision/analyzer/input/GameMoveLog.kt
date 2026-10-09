package com.match3vision.analyzer.input

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.moves.PlayMoveRanker

/**
 * One JSON object per played move, for later offline learning.
 *
 * The line carries the board labels, the ranking, and the settle result.
 * It does not carry an account, a name, or any other personal identifier.
 * Failed and uncertain moves are kept.
 */
object GameMoveLog {
    data class BoardView(
        val timestampMs: Long,
        val labels: List<List<String>>,
        val unknownCells: List<String>,
        val gridConfidence: Float,
        val boardConfidence: Float,
        val unknownCount: Int,
    )

    data class Pending(
        val moveNumber: Int,
        val sessionStartedAtMs: Long,
        val before: BoardView,
        val candidates: List<PlayMoveRanker.Candidate>,
        val selected: String,
    )

    fun view(
        board: Board,
        timestampMs: Long,
        gridConfidence: Float,
        boardConfidence: Float,
        unknownCount: Int,
    ): BoardView = BoardView(
        timestampMs = timestampMs,
        labels = List(Board.SIZE) { row ->
            List(Board.SIZE) { col -> board.get(row, col).color.name }
        },
        unknownCells = buildList {
            board.forEachTile { tile ->
                if (tile.isUnknown) add("${tile.row},${tile.col}")
            }
        },
        gridConfidence = gridConfidence,
        boardConfidence = boardConfidence,
        unknownCount = unknownCount,
    )

    fun finish(
        pending: Pending,
        after: BoardView?,
        verification: String,
        settleMs: Long,
        elapsedMs: Long,
        stopReason: String,
        userInterference: Boolean,
    ): String {
        val selected = pending.candidates.firstOrNull { it.move.toString() == pending.selected }
        val predictedBlue = selected?.blueCleared
        val predictedExtra = selected?.extraMove
        val actualBlue = blueEstimate(pending.before, after)
        val blueUncertain = after == null || after.unknownCount > pending.before.unknownCount
        val blueDelta = if (predictedBlue == null || actualBlue == null) null else actualBlue - predictedBlue
        return line(
            pending = pending,
            after = after,
            verification = verification,
            settleMs = settleMs,
            elapsedMs = elapsedMs,
            stopReason = stopReason,
            userInterference = userInterference,
            predictedBlue = predictedBlue,
            predictedExtra = predictedExtra,
            actualBlue = actualBlue,
            blueUncertain = blueUncertain,
            blueDelta = blueDelta,
            extraMoveGranted = "not detectable",
        )
    }

    /** A gesture started and the session stopped before a settled after-board. */
    fun unfinished(pending: Pending, stopReason: String, nowMs: Long): String =
        finish(
            pending = pending,
            after = null,
            verification = "UNFINISHED",
            settleMs = (nowMs - pending.before.timestampMs).coerceAtLeast(0L),
            elapsedMs = (nowMs - pending.sessionStartedAtMs).coerceAtLeast(0L),
            stopReason = stopReason,
            userInterference = stopReason.contains("user interference", ignoreCase = true),
        )

    private fun blueEstimate(before: BoardView, after: BoardView?): Int? {
        if (after == null) return null
        return blueCount(before.labels) - blueCount(after.labels)
    }

    private fun blueCount(labels: List<List<String>>): Int =
        labels.sumOf { row -> row.count { it == "B" } }

    private fun line(
        pending: Pending,
        after: BoardView?,
        verification: String,
        settleMs: Long,
        elapsedMs: Long,
        stopReason: String,
        userInterference: Boolean,
        predictedBlue: Int?,
        predictedExtra: Boolean?,
        actualBlue: Int?,
        blueUncertain: Boolean,
        blueDelta: Int?,
        extraMoveGranted: String,
    ): String = buildString {
        append('{')
        field("kind", "move")
        field("moveNumber", pending.moveNumber.toString(), number = true)
        append("\"before\":")
        board(pending.before)
        append(',')
        append("\"decision\":{")
        append("\"candidates\":[")
        pending.candidates.forEachIndexed { index, candidate ->
            if (index > 0) append(',')
            candidate(candidate, selected = candidate.move.toString() == pending.selected)
        }
        append("],")
        field("selected", pending.selected)
        append("\"extraMoveRule\":").append(quote(PlayMoveRanker.EXTRA_MOVE_RULE.trim()))
        append('}')
        append(',')
        append("\"after\":{")
        if (after == null) {
            append("\"board\":null")
        } else {
            append("\"board\":")
            board(after)
        }
        append(',')
        numberField("blueClearedEstimate", actualBlue)
        field("blueEstimateUncertain", blueUncertain.toString(), raw = true)
        field("extraMoveGranted", extraMoveGranted)
        numberField("settleMs", settleMs)
        append("\"verification\":").append(quote(verification))
        append("},")
        append("\"session\":{")
        numberField("moveNumber", pending.moveNumber)
        numberField("elapsedMs", elapsedMs)
        field("stopReason", stopReason)
        append("\"userInterference\":").append(userInterference)
        append("},")
        append("\"error\":{")
        numberField("predictedBlue", predictedBlue)
        numberField("actualBlueEstimate", actualBlue)
        numberField("blueDelta", blueDelta)
        field("blueUncertain", blueUncertain.toString(), raw = true)
        field("predictedExtraMove", (predictedExtra ?: false).toString(), raw = true)
        field("extraMoveGranted", extraMoveGranted)
        append("\"extraMoveError\":\"not comparable\"")
        append("}}")
    }

    private fun StringBuilder.board(view: BoardView) {
        append('{')
        numberField("timestampMs", view.timestampMs)
        append("\"labels\":[")
        view.labels.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) append(',')
            append('[')
            row.forEachIndexed { colIndex, label ->
                if (colIndex > 0) append(',')
                append(quote(label))
            }
            append(']')
        }
        append("],")
        append("\"unknownCells\":[")
        view.unknownCells.forEachIndexed { index, cell ->
            if (index > 0) append(',')
            append(quote(cell))
        }
        append("],")
        numberField("gridConfidence", view.gridConfidence)
        numberField("boardConfidence", view.boardConfidence)
        append("\"unknownCount\":").append(view.unknownCount)
        append('}')
    }

    private fun StringBuilder.candidate(candidate: PlayMoveRanker.Candidate, selected: Boolean) {
        append('{')
        field("move", candidate.move.toString())
        numberField("matchLen", candidate.matchLen)
        field("extraMove", candidate.extraMove.toString(), raw = true)
        numberField("blueCleared", candidate.blueCleared)
        numberField("totalCleared", candidate.totalCleared)
        numberField("cascadeSteps", candidate.cascadeSteps)
        field("uncertain", candidate.uncertain.toString(), raw = true)
        field("lowerRow", candidate.lowerRow.toString(), number = true)
        field("specials", candidate.specials)
        append("\"selected\":").append(selected)
        append('}')
    }

    private fun StringBuilder.field(name: String, value: String, raw: Boolean = false, number: Boolean = false) {
        append('"').append(name).append("\":")
        if (raw || number) append(value) else append(quote(value))
        append(',')
    }

    private fun StringBuilder.numberField(name: String, value: Int?) {
        append('"').append(name).append("\":")
        append(value?.toString() ?: "null")
        append(',')
    }

    private fun StringBuilder.numberField(name: String, value: Long) {
        append('"').append(name).append("\":")
        append(value)
        append(',')
    }

    private fun StringBuilder.numberField(name: String, value: Float) {
        append('"').append(name).append("\":")
        append(if (value.isFinite()) "%.4f".format(java.util.Locale.US, value) else "null")
        append(',')
    }

    private fun quote(raw: String): String = buildString(raw.length + 2) {
        append('"')
        for (ch in raw) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(ch)
            }
        }
        append('"')
    }
}
