package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.capture.ContentRoi
import com.match3vision.analyzer.overlay.OwnerStatus
import com.match3vision.analyzer.vision.CellVision
import com.match3vision.analyzer.vision.GridGeometry
import com.match3vision.analyzer.vision.GridMethod
import com.match3vision.analyzer.vision.SpecialType
import com.match3vision.analyzer.vision.TileColor
import com.match3vision.analyzer.vision.TileShape
import com.match3vision.analyzer.vision.ValidationResult
import com.match3vision.analyzer.vision.VisionBoard
import com.match3vision.analyzer.vision.VisionResult
import org.junit.Test

/** Review fixes for 0.27.1. Each case is one of the six required regressions. */
class PlaySettle0271Test {
    @Test
    fun unverifiedMove1_recoversOnTheNextSwipe() {
        val session = FiveMoveSession()
        session.arm(0L)
        session.enableAutoProbe()
        val first = session.requestDispatch(gates(1_000L, selfCheck = false))
        assertThat(first).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
        session.consumePermit((first as FiveMoveSession.Decision.Go).permit)
        session.noteGesture(gesture(startedAtMs = 1_000L, beforeHash = 11L))
        session.onSettle(pass(nowMs = 3_600L, hash = 11L, sequence = 2L))
        val retry = session.onSettle(pass(nowMs = 4_000L, hash = 11L, sequence = 3L))
        assertThat(retry).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        val missed = session.onSettle(pass(nowMs = 10_000L, hash = 11L, sequence = 4L))
        assertThat(missed).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(session.swipesVerified).isEqualTo(0)
        val again = session.requestDispatch(gates(12_000L, selfCheck = false))
        assertThat(again).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
        session.consumePermit((again as FiveMoveSession.Decision.Go).permit)
        session.noteGesture(gesture(startedAtMs = 12_000L, beforeHash = 11L))
        session.onSettle(pass(nowMs = 14_600L, hash = 20L, sequence = 6L))
        val verified = session.onSettle(pass(nowMs = 15_000L, hash = 20L, sequence = 7L))
        assertThat(verified).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(session.swipesVerified).isEqualTo(1)
        assertThat(session.takeAutoSave()).isTrue()
    }

    @Test
    fun menuSingleFrame_doesNotStop() {
        val session = FiveMoveSession()
        session.arm(0L)
        val one = session.notePlayHud(PlayGate.MENU, 0L)
        assertThat(one).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        session.notePlayHud(PlayGate.OURS, 500L)
        val afterOurs = session.notePlayHud(PlayGate.MENU, 1_000L)
        assertThat(afterOurs).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)

        val held = FiveMoveSession()
        held.arm(0L)
        held.notePlayHud(PlayGate.MENU, 0L)
        held.notePlayHud(PlayGate.MENU, 1_000L)
        val early = held.notePlayHud(PlayGate.MENU, 2_000L)
        assertThat(early).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        val stopped = FiveMoveSession()
        stopped.arm(0L)
        stopped.notePlayHud(PlayGate.MENU, 0L)
        stopped.notePlayHud(PlayGate.MENU, 1_500L)
        val pop = stopped.notePlayHud(PlayGate.MENU, 3_000L)
        assertThat(pop).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(OwnerStatus.hu((pop as FiveMoveSession.Decision.Stop).reason))
            .isEqualTo("Menü vagy felugró ablak – leálltam")
    }

    @Test
    fun boosterThenCascade_doesNotSwipeUntilPlayable() {
        val session = FiveMoveSession()
        session.arm(0L)
        val go = session.requestDispatch(gates(1_000L)) as FiveMoveSession.Decision.Go
        session.consumePermit(go.permit)
        session.noteGesture(gesture(startedAtMs = 1_000L, beforeHash = 1L))
        session.onSettle(pass(nowMs = 3_600L, hash = 4L, sequence = 2L))
        session.onSettle(pass(nowMs = 4_000L, hash = 4L, sequence = 3L))
        assertThat(session.swipesVerified).isEqualTo(1)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        session.armBoosterSettle(
            x = 170f,
            y = 923f,
            nowMs = 5_000L,
            playExport = "booster",
            swipeSequence = 3L,
            beforeHash = 4L,
            beforeLabel = 4L,
            beforeCircles = 8,
        )
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.SETTLING)
        assertThat(session.requestDispatch(gates(5_100L))).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        for (step in 1..3) {
            val cascading = session.onSettle(
                pass(nowMs = 5_000L + step * 1_000L, hash = 50L + step, sequence = 3L + step),
            )
            assertThat(cascading).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
            assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.SETTLING)
            assertThat(session.requestDispatch(gates(5_000L + step * 1_000L)))
                .isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        }
        session.onSettle(pass(nowMs = 9_000L, hash = 9L, sequence = 8L))
        val playable = session.onSettle(pass(nowMs = 9_400L, hash = 9L, sequence = 9L))
        assertThat(playable).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(session.swipesVerified).isEqualTo(1)
        assertThat(session.requestDispatch(gates(9_500L))).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
    }

    @Test
    fun oneFramePerSecond_waitsAtMostEightSeconds() {
        assertThat(FiveMoveSession.scaled(FiveMoveSession.POST_SWIPE_MS, 1_000L)).isEqualTo(5_000L)
        assertThat(FiveMoveSession.scaled(FiveMoveSession.POST_SWIPE_BIG_MS, 1_000L)).isEqualTo(8_000L)
        assertThat(FiveMoveSession.scaled(FiveMoveSession.POST_SWIPE_MS, 10_000L)).isEqualTo(5_000L)
        val session = FiveMoveSession()
        session.arm(0L)
        val go = session.requestDispatch(gates(0L)) as FiveMoveSession.Decision.Go
        session.consumePermit(go.permit)
        session.noteGesture(gesture(startedAtMs = 0L, beforeHash = 1L))
        val post = FiveMoveSession.scaled(FiveMoveSession.POST_SWIPE_MS, 1_000L)
        val firstCapture = post
        val secondCapture = post + 1_000L
        assertThat(secondCapture).isAtMost(8_000L)
        session.onSettle(
            pass(nowMs = firstCapture + 2_000L, hash = 9L, sequence = 2L, ageMs = 2_000L, cadenceMs = 1_000L),
        )
        val verified = session.onSettle(
            pass(nowMs = secondCapture + 2_000L, hash = 9L, sequence = 3L, ageMs = 2_000L, cadenceMs = 1_000L),
        )
        assertThat(verified).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(session.verifiedCount).isEqualTo(1)
    }

    @Test
    fun oneFlickeringCell_isPlayable() {
        val base = Board.fromColors(Array(7) { Array(7) { TileColor.R } })
        val flicker = base.setCopy(2, 3, base.get(2, 3).copy(color = TileColor.B))
        val two = flicker.setCopy(1, 1, flicker.get(1, 1).copy(color = TileColor.G))
        assertThat(base.labelHash()).isNotEqualTo(flicker.labelHash())
        assertThat(Board.labelsWithinOne(base.labelKeys(), flicker.labelKeys())).isTrue()
        assertThat(Board.labelsWithinOne(base.labelKeys(), two.labelKeys())).isFalse()
        val unknown = base.setCopy(
            0,
            0,
            base.get(0, 0).copy(color = TileColor.UNKNOWN, shape = TileShape.UNKNOWN),
        )
        val unknownAndFlicker = flicker.setCopy(0, 0, unknown.get(0, 0))
        assertThat(Board.labelsWithinOne(unknown.labelKeys(), unknownAndFlicker.labelKeys())).isTrue()

        val session = FiveMoveSession()
        session.arm(0L)
        val go = session.requestDispatch(gates(0L)) as FiveMoveSession.Decision.Go
        session.consumePermit(go.permit)
        session.noteGesture(gesture(startedAtMs = 0L, beforeHash = 1L))
        session.onSettle(
            pass(nowMs = 2_600L, hash = base.labelHash(), sequence = 2L, keys = base.labelKeys()),
        )
        val verified = session.onSettle(
            pass(nowMs = 3_000L, hash = flicker.labelHash(), sequence = 3L, keys = flicker.labelKeys()),
        )
        assertThat(verified).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(session.verifiedCount).isEqualTo(1)
    }

    @Test
    fun settleAge_isAcceptedByTheDispatchGate() {
        val age = 4_000L
        val limit = FiveMoveSession.SETTLE_FRAME_AGE_MS
        assertThat(age).isGreaterThan(GestureFailSafe.MAX_FRAME_AGE_MS)
        assertThat(age).isAtMost(limit)
        val loop = InputLoopController()
        val stale = loop.runAnalyzeAndMaybeInput(heldVision(), cycle(age))
        assertThat(stale.reason).contains("stale")
        val compatible = loop.runAnalyzeAndMaybeInput(heldVision(), cycle(age, limit))
        assertThat(compatible.reason).doesNotContain("stale")
        val refused = DispatchRecheck.evaluate(
            permit(maxAge = GestureFailSafe.MAX_FRAME_AGE_MS),
            nowElapsedMs = 5_000L,
        )
        assertThat(refused.allow).isFalse()
        assertThat(refused.reason).contains("stale")
        val allowed = DispatchRecheck.evaluate(permit(maxAge = limit), nowElapsedMs = 5_000L)
        assertThat(allowed.allow).isTrue()
    }

    private fun gesture(startedAtMs: Long, beforeHash: Long) = FiveMoveSession.GestureFact(
        startedAtMs = startedAtMs,
        nowMs = startedAtMs + 100L,
        callbackCompleted = true,
        cancelled = false,
        cells = "(0,0)↔(0,1)",
        fromX = 1f,
        fromY = 1f,
        toX = 2f,
        toY = 1f,
        beforeHash = beforeHash,
        beforeUnknown = 0,
        beforeLabel = beforeHash,
    )

    private fun gates(nowMs: Long, selfCheck: Boolean = true) = FiveMoveSession.Gates(
        nowMs = nowMs,
        a11yConnected = true,
        selfCheckMeasured = selfCheck,
        overlayCollapsed = true,
        overlayOutsideRoi = true,
        visionPass = true,
        frameFresh = true,
        ownUi = false,
        msSinceCollapse = FiveMoveSession.MIN_POST_COLLAPSE_MS,
        roiPlausible = true,
        frameSequence = 40L,
    )

    private fun pass(
        nowMs: Long,
        hash: Long,
        sequence: Long,
        ageMs: Long = 0L,
        cadenceMs: Long = 0L,
        keys: LongArray? = null,
    ) = FiveMoveSession.SettleSample(
        nowMs = nowMs,
        boardHash = hash,
        diffFraction = 0.1f,
        frameFresh = true,
        roiPlausible = true,
        visionPass = true,
        unknownCount = 0,
        ownUi = false,
        a11yConnected = true,
        frameSequence = sequence,
        labelHash = hash,
        labelKeys = keys,
        frameAgeMs = ageMs,
        cadenceMedianMs = cadenceMs,
    )

    private fun cycle(ageMs: Long, maxAgeMs: Long = GestureFailSafe.MAX_FRAME_AGE_MS) = RuntimeCycleContext(
        a11yConnected = true,
        captureOn = true,
        hasFrame = true,
        frameAgeMs = ageMs,
        screenWidth = 1080,
        screenHeight = 2400,
        frameWidth = 1080,
        frameHeight = 2400,
        maxFrameAgeMs = maxAgeMs,
    )

    private fun permit(maxAge: Long) = DispatchPermit(
        a11yConnected = true,
        captureOn = true,
        hasFrame = true,
        frameAgeMs = 4_000L,
        visionPass = true,
        inputEnabled = true,
        screenWidth = 1080,
        screenHeight = 2400,
        frameWidth = 1080,
        frameHeight = 2400,
        gesture = GestureSpec(120f, 400f, 200f, 400f, 120L),
        simulated = false,
        sequenceAllowed = true,
        capturedElapsedMs = 1_000L,
        maxFrameAgeMs = maxAge,
    )

    private fun heldVision(): VisionResult = VisionResult(
        board = VisionBoard(Array(7) { Array(7) { cell() } }),
        grid = GridGeometry.evenSplit(ContentRoi(0, 0, 70, 70), 0.97f),
        unknownCount = 4,
        confidence = 0.4f,
        boardConfidence = 0.90f,
        gridConfidence = 0.97f,
        validation = ValidationResult.Hold("HOLD: grid confidence 0.970 < 0.980"),
        method = GridMethod.PROJECTION,
    )

    private fun cell() = CellVision(
        color = TileColor.R,
        shape = TileShape.CIRCLE,
        special = SpecialType.NONE,
        occluded = false,
        confidence = 1f,
        isUnknown = false,
    )
}
