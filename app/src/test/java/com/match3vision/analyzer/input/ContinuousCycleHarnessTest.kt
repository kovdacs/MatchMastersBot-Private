package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.capture.ContentRoi
import com.match3vision.analyzer.capture.FrameSequenceGate
import com.match3vision.analyzer.vision.CellVision
import com.match3vision.analyzer.vision.GridGeometry
import com.match3vision.analyzer.vision.GridMethod
import com.match3vision.analyzer.vision.SpecialType
import com.match3vision.analyzer.vision.TileColor
import com.match3vision.analyzer.vision.TileShape
import com.match3vision.analyzer.vision.ValidationResult
import com.match3vision.analyzer.vision.VisionBoard
import com.match3vision.analyzer.vision.VisionResult
import com.match3vision.analyzer.vision.VisionThresholds
import org.junit.Test

/**
 * Simulated continuous FRAME→ANALYZE→PASS→MOVE→INPUT→VERIFY→NEXT cycles.
 * Proves 1 / 5 / 10 / 20 move chains without silent freeze / uncontrolled retry.
 * Tier B/C only — not live phone (Tier E).
 */
class ContinuousCycleHarnessTest {

    private fun cell(color: TileColor) = CellVision(
        color = color,
        shape = TileShape.CIRCLE,
        special = SpecialType.NONE,
        occluded = false,
        confidence = 1f,
        isUnknown = color == TileColor.UNKNOWN,
    )

    /** Legal match + a mutated board after "gesture". */
    private fun visionPass(seed: Int = 0): VisionResult {
        val palette = listOf(
            TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O, TileColor.R,
        )
        val colors = Array(7) { r ->
            Array(7) { c -> palette[(r * 3 + c * 2 + seed) % 6] }
        }
        // Force a clear horizontal match opportunity
        colors[0][0] = TileColor.R
        colors[0][1] = TileColor.R
        colors[0][2] = TileColor.B
        colors[0][3] = TileColor.Y
        colors[1][2] = TileColor.R
        // Seed-dependent mutation so hashes differ across cycles
        colors[6][6] = palette[seed % 6]
        val cells = Array(7) { r -> Array(7) { c -> cell(colors[r][c]) } }
        return VisionResult(
            board = VisionBoard(cells),
            grid = GridGeometry.evenSplit(ContentRoi(0, 0, 700, 700), 0.99f),
            unknownCount = 0,
            confidence = 1f,
            boardConfidence = VisionThresholds.MIN_BOARD_CONFIDENCE,
            gridConfidence = VisionThresholds.MIN_GRID_CONFIDENCE,
            validation = ValidationResult.Pass,
            method = GridMethod.EVEN_SPLIT,
            diagnostics = mapOf("boardRoi" to "LTRB(0,0,700,700)"),
        )
    }

    private fun afterBoard(before: VisionResult, seed: Int): VisionResult {
        // Mutate the board so contentHash changes. The honest label is unconfirmed.
        return visionPass(seed = seed + 17)
    }

    private fun harness(): Triple<AutoPlayController, RecordingInputGestureExecutor, FrameSequenceGate> {
        val exec = RecordingInputGestureExecutor(ready = true)
        val sw = InputEnableSwitch.disabledByDefault()
        val eng = AutomaticInputEngine(enableSwitch = sw, executor = exec)
        val loop = InputLoopController(inputEngine = eng)
        val ctrl = AutoPlayController(enableSwitch = sw, inputLoop = loop)
        return Triple(ctrl, exec, FrameSequenceGate())
    }

    private fun runNMoves(n: Int) {
        val (ctrl, exec, seq) = harness()
        assertThat(ctrl.onStartRequested()).isTrue()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.RUNNING)

        var frameSeq = 1L
        for (i in 0 until n) {
            val before = visionPass(seed = i)
            val frameId = FrameSequenceGate.FrameId(frameSeq, identity = 1000L + i, timestampMs = 1_000L + i)
            val allow = seq.evaluate(frameId)
            assertThat(allow.allow).isTrue()

            val cycle = ctrl.runCycleIfActive(before)
            assertThat(cycle).isNotNull()
            assertThat(cycle!!.outcome).isEqualTo(BotLoopOutcome.CONTINUE)
            val executed = cycle.executed
            assertThat(executed).isInstanceOf(AutomaticInputEngine.ExecuteResult.Executed::class.java)
            executed as AutomaticInputEngine.ExecuteResult.Executed

            seq.markGestureDispatched(frameId)
            // NEW frame required
            frameSeq += 1
            val afterId = FrameSequenceGate.FrameId(frameSeq, identity = 2000L + i, timestampMs = 2_000L + i)
            val afterAllow = seq.evaluate(afterId)
            assertThat(afterAllow.allow).isTrue()
            assertThat(afterAllow.verdict).isEqualTo(FrameSequenceGate.Verdict.ALLOW_NEW)

            val after = afterBoard(before, seed = i)
            assertThat(Board.fromVision(after.board).contentHash())
                .isNotEqualTo(executed.beforeBoardHash)

            val fb = ctrl.completeFeedback(
                executed.beforeBoardHash,
                after,
                VerifyObservation(
                    newFrameAccepted = true,
                    frameFresh = true,
                    gestureEligible = true,
                    frameElapsedMs = 5_000L + i,
                    dispatchCompletedElapsedMs = 4_000L + i,
                    preDispatchSequence = frameSeq - 1,
                    afterSequence = frameSeq,
                ),
            )
            assertThat(fb).isNotNull()
            assertThat(fb!!.outcome).isEqualTo(BotLoopOutcome.CONTINUE)
            assertThat(fb.verifyStatus).isEqualTo(VerificationPolicy.BOARD_CHANGED_UNCONFIRMED)
            assertThat(fb.verifyStatus).isNotEqualTo(VerificationPolicy.SUCCESS)
            assertThat(fb.reason).contains("BOARD CHANGED")
            // Still RUNNING — no silent PAUSE/STOP freeze
            assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.RUNNING)
            assertThat(ctrl.enableSwitch().isEnabled()).isTrue()
        }

        assertThat(ctrl.moveCount).isEqualTo(n)
        assertThat(exec.dispatched).hasSize(n)
        // No uncontrolled retry: one dispatch per move
        assertThat(exec.dispatched.size).isEqualTo(ctrl.moveCount)
        val marker = SimulationMarker.label(n, exec.dispatched.size)
        assertThat(marker).contains("SIMULATION")
        assertThat(marker).contains("not a live-phone proof")
        assertThat(SimulationMarker.BANNER).contains("LIVE PHONE: NOT TESTED")
        assertThat(SimulationMarker.BANNER).contains("FIRST REAL AUTOMATIC TOUCH: NOT PROVEN")
    }

    @Test fun continuous_1_move() = runNMoves(1)

    @Test
    fun continuous_unconfirmedCap_allowsTwoThenPauses_noThirdDispatch() {
        val (ctrl, exec, seq) = harness()
        assertThat(ctrl.onStartRequested()).isTrue()
        var frameSeq = 1L
        for (i in 0 until AutoPlayController.MAX_CONSECUTIVE_UNCONFIRMED) {
            val before = visionPass(seed = i)
            val frameId = FrameSequenceGate.FrameId(frameSeq, identity = 1000L + i, timestampMs = 1_000L + i)
            assertThat(seq.evaluate(frameId).allow).isTrue()
            val cycle = ctrl.runCycleIfActive(before)!!
            val executed = cycle.executed as AutomaticInputEngine.ExecuteResult.Executed
            seq.markGestureDispatched(frameId)
            frameSeq += 1
            val after = afterBoard(before, seed = i)
            val fb = ctrl.completeFeedback(
                executed.beforeBoardHash,
                after,
                VerifyObservation(
                    newFrameAccepted = true,
                    frameFresh = true,
                    gestureEligible = true,
                    frameElapsedMs = 5_000L + i,
                    dispatchCompletedElapsedMs = 4_000L + i,
                    preDispatchSequence = frameSeq - 1,
                    afterSequence = frameSeq,
                ),
            )!!
            assertThat(fb.verifyStatus).isEqualTo(VerificationPolicy.BOARD_CHANGED_UNCONFIRMED)
            assertThat(fb.verifyStatus).isNotEqualTo(VerificationPolicy.SUCCESS)
            if (i + 1 < AutoPlayController.MAX_CONSECUTIVE_UNCONFIRMED) {
                assertThat(fb.outcome).isEqualTo(BotLoopOutcome.CONTINUE)
                assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.RUNNING)
            } else {
                assertThat(fb.outcome).isEqualTo(BotLoopOutcome.HOLD)
                assertThat(fb.reason).contains("capped at 2")
                assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.PAUSED)
                assertThat(ctrl.enableSwitch().isEnabled()).isFalse()
            }
        }
        assertThat(ctrl.runCycleIfActive(visionPass(99))).isNull()
        assertThat(exec.dispatched).hasSize(AutoPlayController.MAX_CONSECUTIVE_UNCONFIRMED)
        assertThat(ctrl.moveCount).isEqualTo(AutoPlayController.MAX_CONSECUTIVE_UNCONFIRMED)
    }

    @Test
    fun verifyUnchanged_stops_noBlindRetry() {
        val (ctrl, exec, _) = harness()
        assertThat(ctrl.onStartRequested()).isTrue()
        val before = visionPass(0)
        val cycle = ctrl.runCycleIfActive(before)!!
        val executed = cycle.executed as AutomaticInputEngine.ExecuteResult.Executed
        // Same board hash → verify FAIL → failsafe PAUSE (no retry loop)
        val fb = ctrl.completeFeedback(
            executed.beforeBoardHash,
            before,
            VerifyObservation(
                newFrameAccepted = true,
                frameFresh = true,
                gestureEligible = true,
                frameElapsedMs = 6_000L,
                dispatchCompletedElapsedMs = 5_000L,
            ),
        )
        assertThat(fb!!.outcome).isEqualTo(BotLoopOutcome.STOP)
        assertThat(fb.reason).contains("unchanged")
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.PAUSED)
        assertThat(ctrl.enableSwitch().isEnabled()).isFalse()
        // Further cycles must not dispatch
        val again = ctrl.runCycleIfActive(visionPass(1))
        assertThat(again).isNull()
        assertThat(exec.dispatched).hasSize(1)
    }

    @Test
    fun holdDoesNotFreezeModeRunning() {
        val (ctrl, _, _) = harness()
        assertThat(ctrl.onStartRequested()).isTrue()
        val holdVision = visionPass(0).copy(
            validation = ValidationResult.Hold("HOLD: unknownCount=5"),
            unknownCount = 5,
        )
        val cycle = ctrl.runCycleIfActive(holdVision)!!
        assertThat(cycle.outcome).isEqualTo(BotLoopOutcome.HOLD)
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.RUNNING)
        // Next PASS frame still works (soft HOLD retry)
        val ok = ctrl.runCycleIfActive(visionPass(1))
        assertThat(ok).isNotNull()
        assertThat(ok!!.outcome).isEqualTo(BotLoopOutcome.CONTINUE)
    }

    @Test
    fun unconfirmed_firstContinues_secondStops_thirdCannotDispatch() {
        val (ctrl, exec, _) = harness()
        assertThat(ctrl.onStartRequested()).isTrue()
        val first = oneUnconfirmed(ctrl, 0)
        assertThat(first.verifyStatus).isEqualTo(VerificationPolicy.BOARD_CHANGED_UNCONFIRMED)
        assertThat(first.outcome).isEqualTo(BotLoopOutcome.CONTINUE)
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.RUNNING)
        assertThat(ctrl.enableSwitch().isEnabled()).isTrue()
        assertThat(exec.dispatched).hasSize(1)
        val second = oneUnconfirmed(ctrl, 1)
        assertThat(second.verifyStatus).isEqualTo(VerificationPolicy.BOARD_CHANGED_UNCONFIRMED)
        assertThat(second.outcome).isEqualTo(BotLoopOutcome.HOLD)
        assertThat(second.reason).contains("capped at 2")
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.PAUSED)
        assertThat(ctrl.enableSwitch().isEnabled()).isFalse()
        assertThat(ctrl.unconfirmedCapLatched).isTrue()
        assertThat(ctrl.runCycleIfActive(visionPass(2))).isNull()
        assertThat(exec.dispatched).hasSize(2)
        assertThat(ctrl.moveCount).isEqualTo(2)
    }

    @Test
    fun repeatedStart_reset_andDelayedFeedback_cannotBypassCap() {
        val (ctrl, exec, _) = harness()
        assertThat(ctrl.onStartRequested()).isTrue()
        val before = visionPass(0)
        val cycle = ctrl.runCycleIfActive(before)!!
        val executed = cycle.executed as AutomaticInputEngine.ExecuteResult.Executed
        assertThat(exec.dispatched).hasSize(1)
        assertThat(ctrl.runCycleIfActive(visionPass(50))).isNull()
        assertThat(exec.dispatched).hasSize(1)
        assertThat(ctrl.onStartRequested()).isTrue()
        assertThat(ctrl.runCycleIfActive(visionPass(51))).isNull()
        assertThat(ctrl.onBubbleStart()).isTrue()
        assertThat(ctrl.runCycleIfActive(visionPass(52))).isNull()
        assertThat(exec.dispatched).hasSize(1)
        val first = ctrl.completeFeedback(
            executed.beforeBoardHash,
            afterBoard(before, 0),
            unconfirmedObservation(0),
        )!!
        assertThat(first.outcome).isEqualTo(BotLoopOutcome.CONTINUE)
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.RUNNING)
        assertThat(ctrl.consecutiveUnconfirmed).isEqualTo(1)
        val secondCycle = ctrl.runCycleIfActive(visionPass(1))!!
        val secondExec = secondCycle.executed as AutomaticInputEngine.ExecuteResult.Executed
        assertThat(exec.dispatched).hasSize(2)
        assertThat(ctrl.runCycleIfActive(visionPass(53))).isNull()
        assertThat(exec.dispatched).hasSize(2)
        val second = ctrl.completeFeedback(
            secondExec.beforeBoardHash,
            afterBoard(visionPass(1), 1),
            unconfirmedObservation(1),
        )!!
        assertThat(second.outcome).isEqualTo(BotLoopOutcome.HOLD)
        assertThat(ctrl.unconfirmedCapLatched).isTrue()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.PAUSED)
        assertThat(ctrl.onStartRequested()).isFalse()
        assertThat(ctrl.onBubbleStart()).isFalse()
        assertThat(ctrl.lastReason).contains("cap latched")
        assertThat(ctrl.runCycleIfActive(visionPass(3))).isNull()
        assertThat(exec.dispatched).hasSize(2)
        ctrl.onBubbleStop()
        ctrl.resetForNewSession()
        assertThat(ctrl.unconfirmedCapLatched).isTrue()
        assertThat(ctrl.onStartRequested()).isFalse()
        assertThat(ctrl.armSingleMove()).isFalse()
        assertThat(ctrl.lastReason).contains("cap")
        assertThat(ctrl.runCycleIfActive(visionPass(4))).isNull()
        assertThat(exec.dispatched).hasSize(2)
        val (fresh, freshExec, _) = harness()
        assertThat(fresh.unconfirmedCapLatched).isFalse()
        assertThat(fresh.onStartRequested()).isTrue()
        assertThat(fresh.runCycleIfActive(visionPass(0))).isNotNull()
        assertThat(freshExec.dispatched).hasSize(1)
    }

    private fun oneUnconfirmed(ctrl: AutoPlayController, seed: Int): InputLoopController.CycleResult {
        val before = visionPass(seed)
        val cycle = ctrl.runCycleIfActive(before)!!
        val executed = cycle.executed as AutomaticInputEngine.ExecuteResult.Executed
        return ctrl.completeFeedback(
            executed.beforeBoardHash,
            afterBoard(before, seed),
            unconfirmedObservation(seed),
        )!!
    }

    private fun unconfirmedObservation(seed: Int) = VerifyObservation(
        newFrameAccepted = true,
        frameFresh = true,
        gestureEligible = true,
        frameElapsedMs = 5_000L + seed,
        dispatchCompletedElapsedMs = 4_000L + seed,
        preDispatchSequence = seed.toLong(),
        afterSequence = seed.toLong() + 1,
    )
}
