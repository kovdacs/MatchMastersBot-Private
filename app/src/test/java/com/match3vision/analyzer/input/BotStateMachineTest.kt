package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BotStateMachineTest {

    @Test
    fun happyPath_cyclesToCaptureAfterVerify() {
        val sm = BotStateMachine()
        assertThat(sm.state).isEqualTo(BotLoopState.IDLE)
        sm.startCapture()
        assertThat(sm.state).isEqualTo(BotLoopState.CAPTURE)
        sm.onValidationPass()
        assertThat(sm.state).isEqualTo(BotLoopState.ANALYZE)
        sm.onAnalysisReady()
        assertThat(sm.state).isEqualTo(BotLoopState.SELECT_MOVE)
        sm.onMoveSelected()
        assertThat(sm.state).isEqualTo(BotLoopState.EXECUTE_INPUT)
        sm.onInputExecuted()
        assertThat(sm.state).isEqualTo(BotLoopState.WAIT_FOR_BOARD)
        sm.onBoardReadyForVerify()
        assertThat(sm.state).isEqualTo(BotLoopState.VERIFY_RESULT)
        sm.onVerifySuccess()
        assertThat(sm.state).isEqualTo(BotLoopState.CAPTURE)
    }

    @Test
    fun validationHold_goesToHold() {
        val sm = BotStateMachine()
        sm.startCapture()
        val t = sm.onValidationHold("HOLD — low grid")
        assertThat(t.outcome).isEqualTo(BotLoopOutcome.HOLD)
        assertThat(sm.state).isEqualTo(BotLoopState.HOLD)
    }

    @Test
    fun unknown_failSafeStop() {
        val sm = BotStateMachine()
        sm.startCapture()
        sm.onValidationPass()
        val t = sm.onUnknownOrUncertain("too many unknowns")
        assertThat(t.outcome).isEqualTo(BotLoopOutcome.STOP)
        assertThat(sm.isTerminal).isTrue()
        assertThat(sm.lastReason.lowercase()).contains("fail-safe")
    }

    @Test
    fun stopIsTerminal_startCaptureNoOp() {
        val sm = BotStateMachine()
        sm.stop("STOP — test")
        val t = sm.startCapture()
        assertThat(t.outcome).isEqualTo(BotLoopOutcome.STOP)
        assertThat(sm.state).isEqualTo(BotLoopState.STOP)
    }
}
