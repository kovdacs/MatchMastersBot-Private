package com.match3vision.analyzer.recording

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.hud.Json
import com.match3vision.analyzer.vision.TileColor
import org.junit.Test

class MatchRecorderExportTest {
    @Test
    fun fullMatch_parsesBoardsAndWhy() {
        val board = Board.fromColors(
            Array(Board.SIZE) { row ->
                Array(Board.SIZE) { col ->
                    listOf(TileColor.R, TileColor.B, TileColor.Y, TileColor.G)[(row + col) % 4]
                }
            },
        )
        val boardJson = """{"note":"a\"b\\c\n"}"""
        val why = "why \"quote\" \\ slash\nline"
        val match = MatchRecorder()
        match.frames().record(1L, 1080, 2400, Long.MAX_VALUE)
        match.boards().record(2L, board, 3, boardJson)
        match.decisions().record(3L, false, emptyList(), why)

        val text = match.exportJson("match-1")
        val root = Json.parse(text) as Json.Obj
        assertThat(root.string("matchId")).isEqualTo("match-1")

        val frames = root.fields["frames"] as Json.Arr
        val frame = frames.items[0] as Json.Obj
        assertThat(frame.string("hash").toLong()).isEqualTo(Long.MAX_VALUE)
        assertThat((frame.fields["w"] as Json.Num).raw).isEqualTo("1080")

        val boards = root.fields["boards"] as Json.Arr
        val stored = boards.items[0] as Json.Obj
        assertThat(stored.string("board")).isEqualTo(boardJson)
        assertThat(stored.string("hash").toLong()).isEqualTo(board.contentHash())
        assertThat((stored.fields["unk"] as Json.Num).raw).isEqualTo("3")

        val decisions = root.fields["decisions"] as Json.Arr
        val decision = decisions.items[0] as Json.Obj
        assertThat(decision.string("why")).isEqualTo(why)
        assertThat((decision.fields["blocked"] as Json.Bool).value).isFalse()
        assertThat((decision.fields["top"] as Json.Arr).items).isEmpty()
    }
}
