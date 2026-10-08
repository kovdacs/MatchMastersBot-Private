package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.CaptureConsent
import com.match3vision.analyzer.capture.CaptureDisplaySize
import com.match3vision.analyzer.capture.ContentRoi
import com.match3vision.analyzer.capture.FrameSequenceGate
import com.match3vision.analyzer.moves.Move
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
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 0.24.3 production-path audit.
 *
 * The accessibility executor is the production channel. [RecordingInputGestureExecutor]
 * cases are the JVM probe of the same controller code and are labeled SIMULATION.
 * A recorded dispatch is not a phone touch.
 */
class ProductionPathIntegrationTest {

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
        roi: ContentRoi = ContentRoi(0, 0, 700, 700),
    ): VisionResult {
        val cells = Array(7) { r -> Array(7) { c -> cell(colors[r][c]) } }
        val unk = colors.sumOf { row -> row.count { it == TileColor.UNKNOWN } }
        return VisionResult(
            board = VisionBoard(cells),
            grid = GridGeometry.evenSplit(roi, gridConf),
            unknownCount = unk,
            confidence = boardConf,
            boardConfidence = boardConf,
            gridConfidence = gridConf,
            validation = gate,
            method = GridMethod.EVEN_SPLIT,
        )
    }

    /** JVM probe. Not the production channel. */
    private fun simulationController(
        ready: Boolean = true,
    ): Pair<AutoPlayController, RecordingInputGestureExecutor> {
        val exec = RecordingInputGestureExecutor(ready = ready)
        check(ProductionPath.isSimulationExecutor(exec))
        val sw = InputEnableSwitch.disabledByDefault()
        val eng = AutomaticInputEngine(enableSwitch = sw, executor = exec)
        val ctrl = AutoPlayController(
            enableSwitch = sw,
            inputLoop = InputLoopController(inputEngine = eng),
        )
        assertThat(
            ctrl.onStartRequested(a11yConnected = true, captureReady = true, overlayReady = true),
        ).isTrue()
        return ctrl to exec
    }

    private fun measured(
        width: Int = 1080,
        height: Int = 2400,
        ageMs: Long = 20L,
        a11y: Boolean = true,
        capture: Boolean = true,
        hasFrame: Boolean = true,
        seq: FrameSequenceGate.Decision? = null,
    ) = ProductionCycleContext.fromLoopObservation(
        a11yConnected = a11y,
        captureManagerPresent = capture,
        hasFrame = hasFrame,
        frameAgeMs = ageMs,
        frameSequenceDecision = seq,
        frameTimestampMs = 1_700_000_000_000L,
        frameWidth = width,
        frameHeight = height,
    )

    @Test
    fun thresholds_unchanged() {
        assertThat(VisionThresholds.MIN_GRID_CONFIDENCE).isEqualTo(0.98f)
        assertThat(VisionThresholds.MIN_BOARD_CONFIDENCE).isEqualTo(0.95f)
        assertThat(VisionThresholds.MAX_UNKNOWN_COUNT).isEqualTo(1)
    }

    @Test
    fun callChain_namesTheProductionStepsInOrder() {
        val chain = ProductionPath.CALL_CHAIN
        val steps = listOf(
            "MainActivity.beginAutoPlaySetup",
            "CaptureService/MediaProjection",
            "FloatingBubbleService.ensureLoopRunning",
            "ProductionCycleContext",
            "AutoPlayController.runCycleIfActive",
            "InputLoopController.runAnalyzeAndMaybeInput",
            "AutomaticInputEngine.tryExecute",
            "AccessibilityGestureExecutor",
            "MatchMastersAccessibilityService.dispatchGesture",
            "VerificationPolicy",
        )
        var at = -1
        for (step in steps) {
            val next = chain.indexOf(step)
            assertThat(next).isGreaterThan(at)
            at = next
        }
    }

    @Test
    fun defaultEngine_isUninstalled_notTheTestRecorder() {
        val eng = AutomaticInputEngine()
        assertThat(eng.executor()).isInstanceOf(UninstalledGestureExecutor::class.java)
        assertThat(eng.executor()).isNotInstanceOf(RecordingInputGestureExecutor::class.java)
        assertThat(eng.executor()).isNotInstanceOf(ShellInputGestureExecutor::class.java)
        assertThat(ProductionPath.role(eng.executor()))
            .isEqualTo(ProductionPath.ExecutorRole.UNINSTALLED)
        assertThat(eng.executorReady()).isFalse()
    }

    @Test
    fun recordingShellAndUninstalled_cannotBeInstalledAsProduction() {
        val refused = listOf(
            RecordingInputGestureExecutor(ready = true),
            ShellInputGestureExecutor { 0 },
            UninstalledGestureExecutor(),
        )
        for (exec in refused) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                ProductionPath.requireProductionExecutor(exec)
            }
            assertThat(error.message).contains("production path refuses")
            assertThat(ProductionPath.isProductionExecutor(exec)).isFalse()
        }
        assertThat(ProductionPath.isSimulationExecutor(RecordingInputGestureExecutor()))
            .isTrue()
        assertThat(SimulationMarker.BANNER).contains("SIMULATION")
        assertThat(SimulationMarker.BANNER).contains("not live phone")
        assertThat(SimulationMarker.label(1, 1)).contains("SIMULATION")
        assertThat(SimulationMarker.label(1, 1)).contains("not a live-phone proof")
    }

    @Test
    fun productionInstall_isAccessibilityExecutor_notRecording() {
        var consulted = 0
        val exec = ProductionInstall.accessibilityExecutor {
            consulted++
            null
        }
        assertThat(exec).isInstanceOf(AccessibilityGestureExecutor::class.java)
        assertThat(exec).isNotInstanceOf(RecordingInputGestureExecutor::class.java)
        assertThat(ProductionPath.role(exec))
            .isEqualTo(ProductionPath.ExecutorRole.PRODUCTION_ACCESSIBILITY)
        assertThat(ProductionPath.isProductionExecutor(exec)).isTrue()
        assertThat(exec.isReady()).isFalse()
        assertThat(consulted).isEqualTo(1)
    }

    @Test
    fun productionExecutor_refusesNullContext_withoutConsultingService() {
        var consulted = 0
        val exec = ProductionInstall.accessibilityExecutor {
            consulted++
            error("service must not be consulted without a measured context")
        }
        val eng = AutomaticInputEngine(
            enableSwitch = InputEnableSwitch(initiallyEnabled = true),
            executor = exec,
        )
        val result = eng.tryExecute(visionPass(), move = null, context = null)
        assertThat(result).isInstanceOf(AutomaticInputEngine.ExecuteResult.Held::class.java)
        assertThat((result as AutomaticInputEngine.ExecuteResult.Held).reason)
            .contains("refusing default readiness")
        assertThat(consulted).isEqualTo(0)
    }

    @Test
    fun productionExecutor_refusesSimulatedContext_withoutConsultingService() {
        var consulted = 0
        val exec = ProductionInstall.accessibilityExecutor {
            consulted++
            error("service must not be consulted for a simulated context")
        }
        val eng = AutomaticInputEngine(
            enableSwitch = InputEnableSwitch(initiallyEnabled = true),
            executor = exec,
        )
        val simulated = measured().copy(simulated = true)
        val result = eng.tryExecute(visionPass(), move = null, context = simulated)
        assertThat(result).isInstanceOf(AutomaticInputEngine.ExecuteResult.Held::class.java)
        assertThat((result as AutomaticInputEngine.ExecuteResult.Held).reason)
            .contains("refuses simulated readiness")
        assertThat(consulted).isEqualTo(0)
    }

    @Test
    fun productionExecutor_nullService_doesNotDispatchEvenIfContextSaysConnected() {
        val exec = ProductionInstall.accessibilityExecutor { null }
        val sw = InputEnableSwitch.disabledByDefault()
        val ctrl = AutoPlayController(
            enableSwitch = sw,
            inputLoop = InputLoopController(
                inputEngine = AutomaticInputEngine(enableSwitch = sw, executor = exec),
            ),
        )
        assertThat(
            ctrl.onStartRequested(a11yConnected = true, captureReady = true, overlayReady = true),
        ).isTrue()
        val cycle = ctrl.runCycleIfActive(visionPass(), measured(a11y = true))
        assertThat(cycle).isNotNull()
        assertThat(cycle!!.executed)
            .isNotInstanceOf(AutomaticInputEngine.ExecuteResult.Executed::class.java)
        assertThat(cycle.gestureStatus).isEqualTo("NOT CREATED")
        assertThat(cycle.lastDispatch).isEqualTo(StartupReadinessGate.LastDispatch.NONE)
        assertThat(cycle.verifyStatus).isNotEqualTo(VerificationPolicy.SUCCESS)
        assertThat(exec.isReady()).isFalse()
    }

    @Test
    fun productionCycleContext_copiesMeasurements_andIsNeverSimulated() {
        val ctx = measured(width = 1080, height = 2400, ageMs = 40L, a11y = false, capture = false)
        assertThat(ctx.simulated).isFalse()
        assertThat(ctx.a11yConnected).isFalse()
        assertThat(ctx.captureOn).isFalse()
        assertThat(ctx.frameAgeMs).isEqualTo(40L)
        assertThat(ctx.frameWidth).isEqualTo(1080)
        assertThat(ctx.screenWidth).isEqualTo(ctx.frameWidth)
        assertThat(ctx.screenHeight).isEqualTo(ctx.frameHeight)
        val space = FrameScreenCoordinatePolicy.assess(
            ctx.frameWidth, ctx.frameHeight, ctx.screenWidth, ctx.screenHeight,
        )
        assertThat(space.mapping)
            .isEqualTo(FrameScreenCoordinatePolicy.Mapping.IDENTITY_FRAME_PIXELS)
        assertThat(space.deviceDependent).isTrue()
    }

    @Test
    fun captureConsent_api34RequestsEntireDisplay_olderSdksUseChooser() {
        assertThat(CaptureConsent.modeForSdk(33)).isEqualTo(CaptureConsent.Mode.LEGACY_CHOOSER)
        assertThat(CaptureConsent.modeForSdk(34))
            .isEqualTo(CaptureConsent.Mode.ENTIRE_DEFAULT_DISPLAY)
        assertThat(CaptureConsent.modeForSdk(35))
            .isEqualTo(CaptureConsent.Mode.ENTIRE_DEFAULT_DISPLAY)
        assertThat(CaptureConsent.ENTIRE_DISPLAY_MIN_SDK).isEqualTo(34)
    }

    @Test
    fun captureSize_prefersMaximumWindowMetrics_sameSpaceAsTouchTest() {
        val preferred = CaptureDisplaySize.choose(
            maximumWindowWidth = 1080,
            maximumWindowHeight = 2400,
            realWidth = 1080,
            realHeight = 2340,
            densityDpi = 420,
        )
        assertThat(preferred.width).isEqualTo(1080)
        assertThat(preferred.height).isEqualTo(2400)
        assertThat(preferred.source).isEqualTo(CaptureDisplaySize.SOURCE_MAXIMUM_WINDOW)

        val fallback = CaptureDisplaySize.choose(0, 0, 1080, 2400, 440)
        assertThat(fallback.width).isEqualTo(1080)
        assertThat(fallback.height).isEqualTo(2400)
        assertThat(fallback.source).isEqualTo(CaptureDisplaySize.SOURCE_REAL_METRICS)

        val unknown = CaptureDisplaySize.choose(0, 0, 0, 0, 0)
        assertThat(unknown.width).isEqualTo(0)
        assertThat(unknown.source).isEqualTo(CaptureDisplaySize.SOURCE_UNKNOWN)
    }

    @Test
    fun coordinate_identityWhenSizesMatch_cellCentersUnchanged_mismatchRefused() {
        val roi = ContentRoi(20, 400, 1060, 1900)
        val grid = GridGeometry.evenSplit(roi, confidence = 0.99f)
        val gesture = TouchCoordinateMapper().toGesture(Move(2, 3, 3, 3), grid)
        val from = grid.cellBox(2, 3)
        val to = grid.cellBox(3, 3)
        assertThat(gesture.startX).isWithin(0.01f).of(from.centerX())
        assertThat(gesture.startY).isWithin(0.01f).of(from.centerY())
        assertThat(gesture.endX).isWithin(0.01f).of(to.centerX())
        assertThat(gesture.endY).isWithin(0.01f).of(to.centerY())

        val same = FrameScreenCoordinatePolicy.assess(1080, 2400, 1080, 2400)
        assertThat(same.mapping)
            .isEqualTo(FrameScreenCoordinatePolicy.Mapping.IDENTITY_FRAME_PIXELS)
        assertThat(same.deviceDependent).isTrue()
        assertThat(same.reason).contains("no dp")

        val mismatch = FrameScreenCoordinatePolicy.assess(1080, 2400, 1440, 3200)
        assertThat(mismatch.mapping).isEqualTo(FrameScreenCoordinatePolicy.Mapping.REFUSED)
        assertThat(mismatch.reason).contains("frame/screen")
        assertThat(mismatch.reason.lowercase()).contains("refusing to scale")
    }

    @Test
    fun coordinate_sizeMismatch_blocksBeforeDispatch_simulationProbe() {
        val (ctrl, exec) = simulationController()
        val ctx = RuntimeCycleContext(
            a11yConnected = true,
            captureOn = true,
            hasFrame = true,
            frameAgeMs = 20L,
            screenWidth = 1440,
            screenHeight = 3200,
            frameWidth = 1080,
            frameHeight = 2400,
            simulated = false,
        )
        val cycle = ctrl.runCycleIfActive(visionPass(), ctx)
        assertThat(exec.dispatched).isEmpty()
        assertThat(cycle!!.outcome).isEqualTo(BotLoopOutcome.STOP)
        assertThat(cycle.coordinateBlocked).isTrue()
        assertThat(cycle.gestureStatus).isEqualTo("NOT CREATED")
        assertThat(cycle.reason).contains("frame/screen")
        assertThat(ProductionPath.isSimulationExecutor(exec)).isTrue()
        assertThat(SimulationMarker.BANNER).contains("SIMULATION")
    }

    @Test
    fun forbidden_missingFrame_noDispatch_simulationProbe() {
        val (ctrl, exec) = simulationController()
        val cycle = ctrl.runCycleIfActive(visionPass(), measured(hasFrame = false, width = 1080, height = 2400))
        assertThat(exec.dispatched).isEmpty()
        assertThat(cycle!!.gestureStatus).isEqualTo("NOT CREATED")
        assertThat(cycle.reason.lowercase()).contains("frame")
        assertThat(ProductionPath.isSimulationExecutor(exec)).isTrue()
    }

    @Test
    fun forbidden_staleFrame_noDispatch_simulationProbe() {
        val (ctrl, exec) = simulationController()
        val cycle = ctrl.runCycleIfActive(
            visionPass(),
            measured(ageMs = GestureFailSafe.MAX_FRAME_AGE_MS + 1),
        )
        assertThat(exec.dispatched).isEmpty()
        assertThat(cycle!!.reason.lowercase()).contains("stale")
        assertThat(cycle.gestureStatus).isEqualTo("NOT CREATED")
    }

    @Test
    fun forbidden_visionHold_noDispatch_simulationProbe() {
        val (ctrl, exec) = simulationController()
        val held = visionFrom(
            colors = Array(7) { Array(7) { TileColor.B } },
            gate = ValidationResult.Hold("HOLD — Decision AI blocked"),
        )
        val cycle = ctrl.runCycleIfActive(held, measured())
        assertThat(exec.dispatched).isEmpty()
        assertThat(cycle!!.visionGate).contains("HOLD")
        assertThat(cycle.gestureStatus).isEqualTo("NOT CREATED")
        assertThat(cycle.verifyStatus).isNotEqualTo(VerificationPolicy.SUCCESS)
    }

    @Test
    fun forbidden_inputDisabled_noDispatch_simulationProbe() {
        val (ctrl, exec) = simulationController()
        ctrl.enableSwitch().setEnabled(false)
        val cycle = ctrl.runCycleIfActive(visionPass(), measured())
        assertThat(cycle).isNull()
        assertThat(exec.dispatched).isEmpty()
        assertThat(ctrl.enableSwitch().isEnabled()).isFalse()
    }

    @Test
    fun forbidden_a11yDisconnected_noDispatch_simulationProbe() {
        val (ctrl, exec) = simulationController()
        val cycle = ctrl.runCycleIfActive(visionPass(), measured(a11y = false))
        assertThat(exec.dispatched).isEmpty()
        assertThat(cycle!!.reason).contains("ACCESSIBILITY: DISCONNECTED")
        assertThat(cycle.gestureStatus).isEqualTo("NOT CREATED")
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.PAUSED)
    }

    @Test
    fun forbidden_offScreenCoordinate_noDispatch_simulationProbe() {
        val (ctrl, exec) = simulationController()
        val cycle = ctrl.runCycleIfActive(visionPass(), measured(width = 80, height = 80))
        assertThat(exec.dispatched).isEmpty()
        assertThat(cycle!!.coordinateBlocked).isTrue()
        assertThat(cycle.reason.lowercase()).contains("off-screen")
        assertThat(cycle.lastDispatch).isNotEqualTo(StartupReadinessGate.LastDispatch.SUCCESS)
    }

    @Test
    fun dispatchSuccess_isNotVerifySuccess_staleFrameCannotVerify_simulationProbe() {
        val (ctrl, exec) = simulationController()
        val cycle = ctrl.runCycleIfActive(visionPass(), measured())
        assertThat(exec.dispatched).hasSize(1)
        assertThat(cycle!!.verifyStatus).isEqualTo(VerificationPolicy.PENDING)
        assertThat(cycle.verifyStatus).isNotEqualTo(VerificationPolicy.SUCCESS)
        assertThat(cycle.lastDispatch).isEqualTo(StartupReadinessGate.LastDispatch.SUCCESS)
        val executed = cycle.executed as AutomaticInputEngine.ExecuteResult.Executed
        val failed = ctrl.completeFeedback(
            executed.beforeBoardHash,
            visionPass(seed = 1),
            VerifyObservation(newFrameAccepted = false, frameFresh = false, reason = "SAME frame"),
        )
        assertThat(failed!!.verifyStatus).isEqualTo(VerificationPolicy.FAILED)
        assertThat(failed.verifyStatus).isNotEqualTo(VerificationPolicy.SUCCESS)
        assertThat(ProductionPath.isSimulationExecutor(exec)).isTrue()
        assertThat(SimulationMarker.BANNER).contains("LIVE PHONE: NOT TESTED")
        assertThat(SimulationMarker.BANNER).contains("FIRST REAL AUTOMATIC TOUCH: NOT PROVEN")
    }

    @Test
    fun verify_onlyNewFreshChangedBoard_canSucceed_andStaysSimulation() {
        val (ctrl, exec) = simulationController()
        val cycle = ctrl.runCycleIfActive(visionPass(seed = 0), measured())
        val executed = cycle!!.executed as AutomaticInputEngine.ExecuteResult.Executed
        assertThat(exec.dispatched).hasSize(1)
        val pending = VerificationPolicy.afterDispatch(dispatchSucceeded = true)
        assertThat(pending).isEqualTo(VerificationPolicy.PENDING)

        val notNew = ctrl.completeFeedback(
            executed.beforeBoardHash,
            visionPass(seed = 2),
            VerifyObservation(newFrameAccepted = true, frameFresh = false),
        )
        assertThat(notNew!!.verifyStatus).isEqualTo(VerificationPolicy.FAILED)

        val (ctrl2, exec2) = simulationController()
        val cycle2 = ctrl2.runCycleIfActive(visionPass(seed = 0), measured())
        val executed2 = cycle2!!.executed as AutomaticInputEngine.ExecuteResult.Executed
        val ok = ctrl2.completeFeedback(
            executed2.beforeBoardHash,
            visionPass(seed = 3),
            VerifyObservation(newFrameAccepted = true, frameFresh = true),
        )
        assertThat(ok!!.verifyStatus).isEqualTo(VerificationPolicy.SUCCESS)
        assertThat(exec2.dispatched).hasSize(1)
        assertThat(ProductionPath.role(exec2))
            .isEqualTo(ProductionPath.ExecutorRole.SIMULATION_RECORDING)
        assertThat(SimulationMarker.label(moves = 1, dispatched = exec2.dispatched.size))
            .contains("SIMULATION")
    }
}
