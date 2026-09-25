package com.match3vision.analyzer.simulation

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.moves.Move
import com.match3vision.analyzer.moves.MoveSimulator as MovesMoveSimulator

/** Facade for ARCHITECTURE path. Analyzer only — never executes input. */
class MoveSimulator(
    private val delegate: MovesMoveSimulator = MovesMoveSimulator(),
) {
    fun simulate(board: Board, move: Move) = delegate.simulate(board, move)
}
