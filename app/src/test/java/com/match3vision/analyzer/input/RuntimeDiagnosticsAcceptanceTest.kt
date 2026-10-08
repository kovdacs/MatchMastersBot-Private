package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.capture.ContentRoi
import com.match3vision.analyzer.capture.FrameSequenceGate
import com.match3vision.analyzer.moves.LegalMoveGenerator
import com.match3vision.analyzer.overlay.LivePipelineStatus
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
 * 0.24.2 acceptance: faulty readiness never dispatches, VERIFY SUCCESS is not
 * implied by dispatch, stale frames are not a new decision, and the bubble
 * names the first blocker. Thresholds stay 0.98 / 0.95 / unk≤1.
 */
class RuntimeDiagnosticsAcceptanceTest {

    private fun cell(color: TileColor) = CellVision(
        color = color,
        shape = TileShape.CIRCLE,
        special = SpecialType.NONE,
        occluded = false,
        confidence = 1f,
        isUnknown = color == TileColor.UNKNOWN,
    )

    private fun visionPass(seed: Int = 0): VisionResult {
        val palette = listOf(
            TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O, TileColor.R,
        )
        val colors = Array(7) { r ->
            Array(7) { c -> palette[(r * 3 + c * 2 + seed) % 6] }
        }
        colors[0][0] = TileColor.R
        colors[0][1] = TileColor.R
        colors[0][2] = TileColor.B
        colors[0][3] = TileColor.Y
        colors[1][2] = TileColor.R
        colors[6][6] = palette[seed % 6]
        return visionFrom(colors)
    }

    private fun visionFrom(
        colors: Array<Array<TileColor>>,
        gate: ValidationResult = ValidationResult.Pass,
        boardConf: Float = VisionThresholds.MIN_BOARD_CONFIDENCE,
        gridConf: Float = VisionThresholds.MIN_GRID_CONFIDENCE,
    ): VisionResult {
        val cells = Array(7) { r -> Array(7) { c -> cell(colors[r][c]) } }
        val unk = colors.sumOf { row -> row.count { it == TileColor.UNKNOWN } }
        return VisionResult(
            board = VisionBoard(cells),
            grid = GridGeometry.evenSplit(ContentRoi(0, 0, 700, 700), gridConf),
            unknownCount = unk,
            confidence = boardConf,
            boardConfidence = boardConf,
            gridConfidence = gridConf,
            validation = gate,
            method = GridMethod.EVEN_SPLIT,
        )
    }

    private fun fresh(
        ageMs: Long = 20L,
        width: Int = 1080,
        height: Int = 2400,
        a11y: Boolean = true,
        capture: Boolean = true,
        hasFrame: Boolean = true,
        seq: FrameSequenceGate.Decision? = null,
    ) = RuntimeCycleContext(
        a11yConnected = a11y,
        captureOn = capture,
        hasFrame = hasFrame,
        frameAgeMs = ageMs,
        frameSequenceDecision = seq,
        screenWidth = width,
        screenHeight = height,
        frameTimestampMs = 1_700_000_000_000L,
        frameWidth = width,
        frameHeight = height,
        simulated = false,
    )

    private fun started(): Pair<AutoPlayController, RecordingInputGestureExecutor> {
        val exec = RecordingInputGestureExecutor(ready = true)
        val sw = InputEnableSwitch.disabledByDefault()
        val eng = AutomaticInputEngine(enableSwitch = sw, executor = exec)
        val ctrl = AutoPlayController(
            enableSwitch = sw,
            inputLoop = InputLoopController(inputEngine = eng),
        )
        PlayPermit.allowContinuousStart()
        assertThat(ctrl.onStartRequested(a11yConnected = true, captureReady = true, overlayReady = true)).isTrue()
        return ctrl to exec
    }

    @Test
    fun thresholds_unchanged() {
        assertThat(VisionThresholds.MIN_GRID_CONFIDENCE).isEqualTo(0.98f)
        assertThat(VisionThresholds.MIN_BOARD_CONFIDENCE).isEqualTo(0.95f)
        assertThat(VisionThresholds.MAX_UNKNOWN_COUNT).isEqualTo(1)
    }

    @Test
    fun dispatchSuccess_isNotVerifySuccess() {
        assertThat(VerificationPolicy.afterDispatch(dispatchSucceeded = true))
            .isEqualTo(VerificationPolicy.PENDING)
        assertThat(VerificationPolicy.afterDispatch(dispatchSucceeded = true))
            .isNotEqualTo(VerificationPolicy.SUCCESS)
        val (ctrl, exec) = started()
        val cycle = ctrl.runCycleIfActive(visionPass(), fresh())
        assertThat(cycle).isNotNull()
        assertThat(cycle!!.lastDispatch).isEqualTo(StartupReadinessGate.LastDispatch.SUCCESS)
        assertThat(cycle.verifyStatus).isEqualTo(VerificationPolicy.PENDING)
        assertThat(cycle.gestureStatus).isEqualTo("CREATED")
        assertThat(exec.dispatched).hasSize(1)
        val text = LivePipelineStatus(
            mode = "RUNNING",
            phase = "GESZTUS",
            frame = "received",
            boardRoi = "LTRB(0,0,700,700)",
            gridConf = 0.99f,
            boardConf = 0.99f,
            unknownCount = 0,
            visionGate = "PASS",
            moveCount = 1,
            selectedMove = cycle.moveLabel,
            a11y = "CONNECTED",
            inputReady = "READY",
            lastDispatch = "SUCCESS",
            verifyStatus = cycle.verifyStatus,
            frameSequence = "seq=1",
            frameAgeMs = 20L,
            captureStatus = "ON",
            firstBlock = null,
            gestureStatus = "CREATED",
            frameTimestampMs = 1_700_000_000_000L,
            frameWidth = 1080,
            frameHeight = 2400,
            frameFreshness = "FRESH",
            moveCandidates = cycle.moveCandidates,
        ).bubbleLines(compact = true)
        assertThat(text).contains("DISPATCH: SUCCESS")
        assertThat(text).contains("VERIFY: PENDING")
        assertThat(text).doesNotContain("VERIFY: SUCCESS")
    }

    @Test
    fun bubble_listsRequiredRuntimeFields() {
        val text = LivePipelineStatus(
            mode = "RUNNING",
            phase = "TARTÁS",
            frame = "received",
            boardRoi = "LTRB(0,0,700,700)",
            gridConf = 0.99f,
            boardConf = 0.96f,
            unknownCount = 1,
            visionGate = "HOLD — unknownCount=3",
            moveCount = 0,
            selectedMove = "none",
            a11y = "DISCONNECTED",
            inputReady = "BLOCKED",
            lastDispatch = "NONE",
            verifyStatus = "NONE",
            frameSequence = "seq=2",
            frameAgeMs = 4_000L,
            captureStatus = "OFF",
            firstBlock = "ACCESSIBILITY: DISCONNECTED",
            gestureStatus = "NOT CREATED",
            frameTimestampMs = 42L,
            frameWidth = 1080,
            frameHeight = 2400,
            frameFreshness = "STALE",
            moveCandidates = 0,
            inputBlockReason = "ACCESSIBILITY: DISCONNECTED",
        ).bubbleLines(compact = true)
        for (token in listOf(
            "AUTO: RUNNING",
            "CAPTURE: OFF",
            "FRAME: t=42 1080x2400",
            "STALE",
            "VISION: HOLD",
            "MOVE: jelölt=0",
            "A11Y: DISCONNECTED",
            "INPUT: BLOCKED",
            "GESTURE: NOT CREATED",
            "DISPATCH: NOT STARTED",
            "VERIFY: PENDING",
            "BLOKK: ACCESSIBILITY: DISCONNECTED",
        )) {
            assertThat(text).contains(token)
        }
    }

    @Test
    fun noFrame_blocksDispatch() {
        val (ctrl, exec) = started()
        val cycle = ctrl.runCycleIfActive(visionPass(), fresh(hasFrame = false))
        assertThat(cycle!!.reason).contains("no frame")
        assertThat(cycle.verifyStatus).isNotEqualTo(VerificationPolicy.SUCCESS)
        assertThat(exec.dispatched).isEmpty()
    }

    @Test
    fun staleFrame_notUsedForNewDecision_thenFreshStillWorks() {
        val (ctrl, exec) = started()
        val stale = ctrl.runCycleIfActive(
            visionPass(),
            fresh(ageMs = GestureFailSafe.MAX_FRAME_AGE_MS + 50),
        )
        assertThat(stale!!.reason.lowercase()).contains("stale")
        assertThat(stale.verifyStatus).isNotEqualTo(VerificationPolicy.SUCCESS)
        assertThat(exec.dispatched).isEmpty()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.RUNNING)
        val ok = ctrl.runCycleIfActive(visionPass(1), fresh())
        assertThat(ok!!.outcome).isEqualTo(BotLoopOutcome.CONTINUE)
        assertThat(exec.dispatched).hasSize(1)
    }

    @Test
    fun sameFrameAfterGesture_blocksBeforeDispatch() {
        val (ctrl, exec) = started()
        val reject = FrameSequenceGate.Decision(
            FrameSequenceGate.Verdict.REJECT_SAME,
            FrameSequenceGate.HOLD_SAME_FRAME,
            allow = false,
        )
        val cycle = ctrl.runCycleIfActive(visionPass(), fresh(seq = reject))
        assertThat(cycle!!.reason).contains("SAME")
        assertThat(exec.dispatched).isEmpty()
    }

    @Test
    fun visionHold_neverReachesDispatch() {
        val (ctrl, exec) = started()
        val hold = visionPass().copy(
            validation = ValidationResult.Hold("HOLD: unknownCount=4"),
            unknownCount = 4,
        )
        val cycle = ctrl.runCycleIfActive(hold, fresh())
        assertThat(cycle!!.outcome).isEqualTo(BotLoopOutcome.HOLD)
        assertThat(cycle.visionGate).contains("HOLD")
        assertThat(cycle.verifyStatus).isNotEqualTo(VerificationPolicy.SUCCESS)
        assertThat(cycle.gestureStatus).isEqualTo("NOT CREATED")
        assertThat(exec.dispatched).isEmpty()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.RUNNING)
    }

    @Test
    fun passButNoLegalMove_blocksDispatch() {
        val colors = noLegalMoveColors()
        val (ctrl, exec) = started()
        val cycle = ctrl.runCycleIfActive(visionFrom(colors), fresh())
        assertThat(cycle!!.moveLabel).isEqualTo("none")
        assertThat(cycle.moveCandidates).isEqualTo(0)
        assertThat(cycle.reason.lowercase()).contains("move")
        assertThat(exec.dispatched).isEmpty()
        val block = LivePipelineStatus.firstBlockingReason(
            mode = AutoPlayController.Mode.RUNNING,
            a11yConnected = true,
            captureOn = true,
            hasFrame = true,
            frameSequenceAllow = true,
            frameSequenceReason = null,
            frameAgeMs = 10L,
            visionPass = true,
            visionHoldReason = null,
            gridConf = 0.99f,
            boardConf = 0.99f,
            unknownCount = 0,
            hasSelectedMove = false,
            inputReady = true,
            lastDispatch = StartupReadinessGate.LastDispatch.NONE,
            verifyStatus = VerificationPolicy.PENDING,
        )
        assertThat(block).contains("MOVE")
    }

    @Test
    fun faultyInputReadiness_blocksDispatchInEveryCase() {
        val vision = visionPass()
        // Switch off.
        run {
            val exec = RecordingInputGestureExecutor(ready = true)
            val sw = InputEnableSwitch.disabledByDefault()
            val loop = InputLoopController(
                inputEngine = AutomaticInputEngine(enableSwitch = sw, executor = exec),
            )
            val cycle = loop.runAnalyzeAndMaybeInput(vision, fresh())
            assertThat(cycle.outcome).isEqualTo(BotLoopOutcome.HOLD)
            assertThat(cycle.reason.lowercase()).contains("disabled")
            assertThat(cycle.inputBlockReason).isNotNull()
            assertThat(exec.dispatched).isEmpty()
        }
        // Channel not ready.
        run {
            val exec = RecordingInputGestureExecutor(ready = false)
            val sw = InputEnableSwitch(initiallyEnabled = true)
            val loop = InputLoopController(
                inputEngine = AutomaticInputEngine(enableSwitch = sw, executor = exec),
            )
            val cycle = loop.runAnalyzeAndMaybeInput(vision, fresh())
            assertThat(cycle.inputReady).isFalse()
            assertThat(exec.dispatched).isEmpty()
        }
        // Accessibility disconnected even if the executor object says ready.
        run {
            val exec = RecordingInputGestureExecutor(ready = true)
            val sw = InputEnableSwitch(initiallyEnabled = true)
            val ctrl = AutoPlayController(
                enableSwitch = sw,
                inputLoop = InputLoopController(
                    inputEngine = AutomaticInputEngine(enableSwitch = sw, executor = exec),
                ),
            )
            PlayPermit.allowContinuousStart()
            assertThat(ctrl.onStartRequested(a11yConnected = true)).isTrue()
            val cycle = ctrl.runCycleIfActive(vision, fresh(a11y = false))
            assertThat(cycle!!.reason).contains("DISCONNECTED")
            assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.PAUSED)
            assertThat(exec.dispatched).isEmpty()
            assertThat(ctrl.runCycleIfActive(vision, fresh())).isNull()
        }
        // Capture off.
        run {
            val (ctrl, exec) = started()
            val cycle = ctrl.runCycleIfActive(vision, fresh(capture = false))
            assertThat(cycle!!.reason).contains("CAPTURE")
            assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.PAUSED)
            assertThat(exec.dispatched).isEmpty()
        }
        // Settings-only a11y never counts as ready.
        val gate = StartupReadinessGate.evaluate(
            runtimeConnected = false,
            settingsEnabled = true,
            captureReady = true,
            overlayReady = true,
            inputSwitchEnabled = true,
        )
        assertThat(gate.inputReady).isFalse()
        assertThat(gate.canEnterRunning).isFalse()
    }

    @Test
    fun offScreenAndUnknownBounds_blockedBeforeDispatch_noRetry() {
        val (ctrl, exec) = started()
        val bad = ctrl.runCycleIfActive(visionPass(), fresh(width = 40, height = 40))
        assertThat(bad!!.outcome).isEqualTo(BotLoopOutcome.STOP)
        assertThat(bad.gestureStatus).isEqualTo("NOT CREATED")
        assertThat(bad.gestureAttemptFailed).isTrue()
        assertThat(bad.coordinateBlocked).isTrue()
        assertThat(bad.verifyStatus).isNotEqualTo(VerificationPolicy.SUCCESS)
        assertThat(exec.dispatched).isEmpty()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.PAUSED)
        assertThat(ctrl.runCycleIfActive(visionPass(1), fresh())).isNull()
        assertThat(exec.dispatched).isEmpty()

        val (ctrl2, exec2) = started()
        val unknown = ctrl2.runCycleIfActive(visionPass(), fresh(width = 0, height = 0))
        assertThat(unknown!!.reason.lowercase()).contains("bounds unknown")
        assertThat(exec2.dispatched).isEmpty()
        assertThat(ctrl2.runCycleIfActive(visionPass(), fresh())).isNull()
    }

    @Test
    fun dispatchFailure_doesNotRetryAndIsNotVerifySuccess() {
        val exec = RecordingInputGestureExecutor(ready = true)
        exec.failNextDispatch(true)
        val sw = InputEnableSwitch.disabledByDefault()
        val ctrl = AutoPlayController(
            enableSwitch = sw,
            inputLoop = InputLoopController(
                inputEngine = AutomaticInputEngine(enableSwitch = sw, executor = exec),
            ),
        )
        PlayPermit.allowContinuousStart()
        assertThat(ctrl.onStartRequested()).isTrue()
        val cycle = ctrl.runCycleIfActive(visionPass(), fresh())
        assertThat(cycle!!.outcome).isEqualTo(BotLoopOutcome.STOP)
        assertThat(cycle.lastDispatch).isEqualTo(StartupReadinessGate.LastDispatch.FAILED)
        assertThat(cycle.verifyStatus).isEqualTo(VerificationPolicy.PENDING)
        assertThat(cycle.gestureStatus).isEqualTo("CREATED")
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.PAUSED)
        // failNext is consumed. A retry would succeed and record a gesture.
        assertThat(ctrl.runCycleIfActive(visionPass(1), fresh())).isNull()
        assertThat(exec.dispatched).isEmpty()
    }

    @Test
    fun verify_requiresNewFreshFrame_andDoesNotTreatDispatchAsSuccess() {
        val (ctrl, exec) = started()
        val before = visionPass(0)
        val cycle = ctrl.runCycleIfActive(before, fresh())!!
        val executed = cycle.executed as AutomaticInputEngine.ExecuteResult.Executed
        assertThat(cycle.verifyStatus).isEqualTo(VerificationPolicy.PENDING)
        val changed = visionPass(3)
        assertThat(Board.fromVision(changed.board).contentHash())
            .isNotEqualTo(executed.beforeBoardHash)

        val staleObs = ctrl.completeFeedback(
            executed.beforeBoardHash,
            changed,
            VerifyObservation(newFrameAccepted = true, frameFresh = false, reason = "stale"),
        )
        assertThat(staleObs!!.verifyStatus).isEqualTo(VerificationPolicy.FAILED)
        assertThat(staleObs.outcome).isEqualTo(BotLoopOutcome.STOP)
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.PAUSED)
        assertThat(exec.dispatched).hasSize(1)
    }

    @Test
    fun verify_nonNewFrame_failedEvenIfBoardWouldDiffer() {
        val loop = InputLoopController(
            inputEngine = AutomaticInputEngine(
                enableSwitch = InputEnableSwitch(initiallyEnabled = true),
                executor = RecordingInputGestureExecutor(ready = true),
            ),
        )
        val before = visionPass(0)
        val cycle = loop.runAnalyzeAndMaybeInput(before, fresh())
        val executed = cycle.executed as AutomaticInputEngine.ExecuteResult.Executed
        val changed = visionPass(4)
        val fb = loop.completeFeedback(
            executed.beforeBoardHash,
            changed,
            VerifyObservation(newFrameAccepted = false, frameFresh = true, reason = "SAME frame"),
        )
        assertThat(fb.verifyStatus).isEqualTo(VerificationPolicy.FAILED)
        assertThat(fb.outcome).isEqualTo(BotLoopOutcome.STOP)
        assertThat(fb.feedback).isNull()
    }

    @Test
    fun verify_boardUnchanged_failed() {
        val (ctrl, _) = started()
        val before = visionPass(0)
        val cycle = ctrl.runCycleIfActive(before, fresh())!!
        val executed = cycle.executed as AutomaticInputEngine.ExecuteResult.Executed
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
        assertThat(fb!!.verifyStatus).isEqualTo(VerificationPolicy.FAILED)
        assertThat(fb.reason.lowercase()).contains("unchanged")
        assertThat(fb.lastDispatch).isEqualTo(StartupReadinessGate.LastDispatch.SUCCESS)
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.PAUSED)
        assertThat(ctrl.runCycleIfActive(visionPass(1), fresh())).isNull()
    }

    @Test
    fun verify_boardChangedButNotVerifiable_failed() {
        val (ctrl, _) = started()
        val before = visionPass(0)
        val cycle = ctrl.runCycleIfActive(before, fresh())!!
        val executed = cycle.executed as AutomaticInputEngine.ExecuteResult.Executed
        val changedButHold = visionPass(5).copy(
            validation = ValidationResult.Hold("HOLD — blur"),
            boardConfidence = 0.2f,
            gridConfidence = 0.2f,
        )
        assertThat(Board.fromVision(changedButHold.board).contentHash())
            .isNotEqualTo(executed.beforeBoardHash)
        val fb = ctrl.completeFeedback(
            executed.beforeBoardHash,
            changedButHold,
            VerifyObservation(
                newFrameAccepted = true,
                frameFresh = true,
                gestureEligible = true,
                frameElapsedMs = 6_000L,
                dispatchCompletedElapsedMs = 5_000L,
            ),
        )
        assertThat(fb!!.verifyStatus).isEqualTo(VerificationPolicy.FAILED)
        assertThat(fb.outcome).isEqualTo(BotLoopOutcome.STOP)
        assertThat(fb.reason.lowercase()).contains("invalid")
    }

    @Test
    fun verify_newFreshChangedBoard_isUnconfirmed_notSuccess() {
        val (ctrl, exec) = started()
        val before = visionPass(0)
        val cycle = ctrl.runCycleIfActive(before, fresh())!!
        val executed = cycle.executed as AutomaticInputEngine.ExecuteResult.Executed
        val after = visionPass(8)
        val fb = ctrl.completeFeedback(
            executed.beforeBoardHash,
            after,
            VerifyObservation(
                newFrameAccepted = true,
                frameFresh = true,
                gestureEligible = true,
                frameElapsedMs = 9_000L,
                dispatchCompletedElapsedMs = 8_000L,
                preDispatchSequence = 3L,
                afterSequence = 4L,
            ),
        )
        assertThat(fb!!.verifyStatus).isEqualTo(VerificationPolicy.BOARD_CHANGED_UNCONFIRMED)
        assertThat(fb.verifyStatus).isNotEqualTo(VerificationPolicy.SUCCESS)
        assertThat(fb.outcome).isEqualTo(BotLoopOutcome.CONTINUE)
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.RUNNING)
        assertThat(exec.dispatched).hasSize(1)
        assertThat(VerificationPolicy.afterDispatch(dispatchSucceeded = true))
            .isEqualTo(VerificationPolicy.PENDING)
    }

    @Test
    fun accessibilityOrCaptureDrop_pauses_andRestartIsExplicit() {
        val (ctrl, exec) = started()
        ctrl.onFailsafePause("ACCESSIBILITY: DISCONNECTED (mid-run)")
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.PAUSED)
        assertThat(ctrl.enableSwitch().isEnabled()).isFalse()
        assertThat(ctrl.runCycleIfActive(visionPass(), fresh())).isNull()
        assertThat(exec.dispatched).isEmpty()
        assertThat(
            ctrl.onStartRequested(a11yConnected = true, captureReady = true, overlayReady = true),
        ).isTrue()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.RUNNING)
        assertThat(ctrl.runCycleIfActive(visionPass(), fresh())!!.outcome)
            .isEqualTo(BotLoopOutcome.CONTINUE)

        ctrl.onCaptureLost()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.PAUSED)
        assertThat(ctrl.lastReason).contains("CAPTURE")
        assertThat(ctrl.runCycleIfActive(visionPass(1), fresh())).isNull()

        assertThat(
            ctrl.onStartRequested(a11yConnected = true, captureReady = true, overlayReady = true),
        ).isTrue()
        ctrl.onBubbleStop("bubble STOP")
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.STOPPED)
        assertThat(ctrl.onStartRequested(a11yConnected = true)).isFalse()
        ctrl.resetForNewSession()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.IDLE)
        assertThat(
            ctrl.onStartRequested(a11yConnected = true, captureReady = true, overlayReady = true),
        ).isTrue()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.RUNNING)
    }

    @Test
    fun idleAfterRejectedStart_showsConcreteBlock_notPressStart() {
        val block = LivePipelineStatus.firstBlockingReason(
            mode = AutoPlayController.Mode.IDLE,
            a11yConnected = false,
            captureOn = false,
            hasFrame = false,
            frameSequenceAllow = true,
            frameSequenceReason = null,
            frameAgeMs = -1L,
            visionPass = false,
            visionHoldReason = null,
            gridConf = -1f,
            boardConf = -1f,
            unknownCount = -1,
            hasSelectedMove = false,
            inputReady = false,
            lastDispatch = StartupReadinessGate.LastDispatch.NONE,
            verifyStatus = VerificationPolicy.PENDING,
            cycleReason = "CAPTURE: OFF — CaptureService még nem él",
        )
        assertThat(block).contains("CAPTURE")
        assertThat(block).doesNotContain("INDÍTÁS")
    }

    @Test
    fun policy_refusesSuccessUnlessEveryVerifyInputHolds() {
        assertThat(
            VerificationPolicy.decide(
                newFrameAccepted = true,
                frameFresh = true,
                frameVerifiable = true,
                boardChanged = true,
            ),
        ).isEqualTo(VerificationPolicy.BOARD_CHANGED_UNCONFIRMED)
        assertThat(
            VerificationPolicy.decide(
                newFrameAccepted = true,
                frameFresh = true,
                frameVerifiable = true,
                boardChanged = true,
            ),
        ).isNotEqualTo(VerificationPolicy.SUCCESS)
        assertThat(
            VerificationPolicy.decide(true, true, true, false),
        ).isEqualTo(VerificationPolicy.FAILED)
        assertThat(
            VerificationPolicy.decide(true, true, false, true),
        ).isEqualTo(VerificationPolicy.FAILED)
        assertThat(
            VerificationPolicy.decide(true, false, true, true),
        ).isEqualTo(VerificationPolicy.FAILED)
        assertThat(
            VerificationPolicy.decide(false, true, true, true),
        ).isEqualTo(VerificationPolicy.FAILED)
    }

    /**
     * Stable 7×7: no run of 3, and no adjacent swap creates one.
     * Found by the same color-match rule as [com.match3vision.analyzer.rules.MatchDetector].
     * Not a change to MoveAnalysis — it is a fixture the real generator scores as empty.
     */
    private fun noLegalMoveColors(): Array<Array<TileColor>> {
        val p = listOf(
            TileColor.R, TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O,
        )
        val rows = listOf(
            listOf(5, 3, 0, 1, 0, 5, 4),
            listOf(5, 4, 0, 5, 0, 1, 4),
            listOf(0, 4, 2, 3, 5, 1, 5),
            listOf(1, 5, 5, 0, 4, 4, 3),
            listOf(0, 1, 3, 2, 4, 5, 0),
            listOf(4, 4, 0, 3, 2, 1, 5),
            listOf(5, 3, 2, 1, 5, 1, 4),
        )
        val colors = Array(7) { r -> Array(7) { c -> p[rows[r][c]] } }
        val moves = LegalMoveGenerator().generate(Board.fromVision(visionFrom(colors).board))
        check(moves.isEmpty()) { "fixture unexpectedly has moves: $moves" }
        return colors
    }
}
