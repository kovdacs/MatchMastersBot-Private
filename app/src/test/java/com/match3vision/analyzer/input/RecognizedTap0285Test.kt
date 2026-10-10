package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.capture.ScreenMeasurement
import com.match3vision.analyzer.play.HelpPolicy
import com.match3vision.analyzer.play.HelpTargets
import com.match3vision.analyzer.play.PlayFlags
import com.match3vision.analyzer.vision.TileColor
import org.junit.Test

/**
 * Fake accessibility channel through the production executor.
 * The session then does what the bubble does with that result.
 */
class RecognizedTap0285Test {

    private class ScriptedChannel(
        private val ready: Boolean = true,
        private val answer: (GestureSpec) -> InputDispatchResult,
    ) : AccessibilityGestureChannel {
        var calls: Int = 0
        var awaited: Boolean? = null
        override fun canDispatchGestures(): Boolean = ready
        override fun diagnose(): String = "scripted ready=$ready"
        override fun dispatchGesture(
            gesture: GestureSpec,
            awaitCompletion: Boolean,
            timeoutMs: Long,
        ): InputDispatchResult {
            calls += 1
            awaited = awaitCompletion
            return answer(gesture)
        }
    }

    private fun permit(gesture: GestureSpec) = DispatchPermit(
        a11yConnected = true,
        captureOn = true,
        hasFrame = true,
        frameAgeMs = 20L,
        visionPass = true,
        inputEnabled = true,
        screenWidth = 1080,
        screenHeight = 2400,
        frameWidth = 1080,
        frameHeight = 2400,
        gesture = gesture,
        simulated = false,
        sequenceAllowed = true,
        capturedElapsedMs = 10_000L,
    )

    private fun executor(
        channel: ScriptedChannel,
        stopped: Boolean = false,
        inputEnabled: Boolean = true,
    ): AccessibilityGestureExecutor {
        val screen = ScreenMeasurement(
            widthPx = 1080,
            heightPx = 2400,
            source = ScreenMeasurement.SOURCE_MAXIMUM_WINDOW,
        )
        return AccessibilityGestureExecutor(
            serviceProvider = { channel },
            nowElapsedMs = { 10_020L },
            liveProbe = LiveDispatchProbe(
                inputEnabled = { inputEnabled },
                stopped = { stopped },
                captureReady = { true },
                screen = { screen },
            ),
        )
    }

    private fun tapGesture() = GestureSpec.tap(180f, 940f, FiveMoveSession.BOOSTER_TAP_MS)

    private fun armed(): FiveMoveSession {
        PlayFlags.boosters = true
        PlayFlags.helps = true
        val session = FiveMoveSession()
        session.arm(0L)
        return session
    }

    private fun applyBooster(session: FiveMoveSession, result: InputDispatchResult, nowMs: Long) =
        RecognizedTap.applyBooster(
            session = session,
            result = result,
            x = 180f,
            y = 940f,
            nowMs = nowMs,
            playExport = "booster",
            swipeSequence = 1L,
            beforeHash = 7L,
            beforeLabel = 7L,
            beforeCircles = 10,
        )

    @Test
    fun boosterTap_settlesOnTwoStableFrames_thenTheNextSwipeGoes() {
        val channel = ScriptedChannel { gesture ->
            InputDispatchResult.Dispatched(gesture, callbackCompleted = true)
        }
        val result = executor(channel).dispatchRecognizedTap(permit(tapGesture()))
        assertThat(channel.calls).isEqualTo(1)
        assertThat(channel.awaited).isTrue()
        assertThat(result).isInstanceOf(InputDispatchResult.Dispatched::class.java)
        assertThat((result as InputDispatchResult.Dispatched).callbackCompleted).isTrue()

        val session = armed()
        assertThat(applyBooster(session, result, 1_000L)).isTrue()
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.SETTLING)
        assertThat(session.boosterAttempts).isEqualTo(1)
        assertThat(session.boosterLatched).isFalse()

        session.onSettle(pass(nowMs = 1_400L, hash = 7L, sequence = 2L))
        val playable = session.onSettle(pass(nowMs = 1_800L, hash = 7L, sequence = 3L))
        assertThat(playable).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        val go = session.requestDispatch(gates(1_900L, frameSequence = 4L))
        assertThat(go).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
    }

    @Test
    fun failedBoosterTap_isNotCounted_andTheNextFrameRetries() {
        val channel = ScriptedChannel {
            InputDispatchResult.Failed("gesture onCancelled (overlay/touch conflict or system interrupt)")
        }
        val result = executor(channel).dispatchRecognizedTap(permit(tapGesture()))
        assertThat(channel.calls).isEqualTo(1)
        val session = armed()
        assertThat(applyBooster(session, result, 1_000L)).isFalse()
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(session.boosterAttempts).isEqualTo(0)
        assertThat(session.boosterLatched).isFalse()
        assertThat(session.boosterGaveUp).isFalse()
        assertThat(session.boosterMayTap()).isTrue()
        val retry = session.considerBoosterFrame(
            nowMs = 1_200L,
            activateVisible = true,
            barFull = true,
            canSendNow = true,
        )
        assertThat(retry).isEqualTo(FiveMoveSession.BoosterStep.TAP)
        assertThat(session.boosterTargetNote).isEqualTo("id unknown")
    }

    @Test
    fun scheduledOnly_doesNotSettle_andTheSwipeGoes() {
        val result = InputDispatchResult.Failed(
            "SCHEDULED_ONLY — gesture scheduled only; callback was not awaited",
        )
        val session = armed()
        assertThat(applyBooster(session, result, 1_000L)).isFalse()
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(session.boosterAttempts).isEqualTo(0)
        assertThat(session.boosterLatched).isFalse()
        val go = session.requestDispatch(gates(1_100L, frameSequence = 2L))
        assertThat(go).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
    }

    @Test
    fun liveStop_blocksTheTapBeforeTheChannel() {
        val channel = ScriptedChannel { gesture ->
            InputDispatchResult.Dispatched(gesture, callbackCompleted = true)
        }
        val result = executor(channel, stopped = true).dispatchRecognizedTap(permit(tapGesture()))
        assertThat(channel.calls).isEqualTo(0)
        assertThat(result).isInstanceOf(InputDispatchResult.Failed::class.java)
        assertThat((result as InputDispatchResult.Failed).reason).contains("TOCTOU")
        val session = armed()
        assertThat(applyBooster(session, result, 1_000L)).isFalse()
        assertThat(session.boosterAttempts).isEqualTo(0)
        assertThat(session.boosterLatched).isFalse()
    }

    @Test
    fun failedHelpTap_leavesTheQuota_andACompletedTapConsumesIt() {
        val session = armed()
        val choice = HelpPolicy.Choice("shuffle", needsTarget = false, reason = "no legal move")
        val first = session.advanceHelp(1_000L, false, true, choice, null, null)
        assertThat(first).isInstanceOf(FiveMoveSession.HelpGesture.Tap::class.java)
        assertThat(session.helpsUsedThisTurn).isEqualTo(0)

        val channel = ScriptedChannel {
            InputDispatchResult.Failed("gesture callback timeout — callback never arrived")
        }
        val failed = executor(channel).dispatchRecognizedTap(permit(tapGesture()))
        assertThat(channel.calls).isEqualTo(1)
        assertThat(RecognizedTap.applyHelp(session, failed, 1_100L)).isFalse()
        assertThat(session.helpsUsedThisTurn).isEqualTo(0)
        assertThat(session.helpPhase).isEqualTo("idle")

        val again = session.advanceHelp(
            1_000L + FiveMoveSession.HELP_ATTEMPT_GAP_MS,
            false,
            true,
            choice,
            null,
            null,
        )
        assertThat(again).isNull()
        assertThat(session.unavailableHelps).contains("shuffle")

        val fresh = armed()
        assertThat(fresh.advanceHelp(1_000L, false, true, choice, null, null))
            .isInstanceOf(FiveMoveSession.HelpGesture.Tap::class.java)
        val completed = InputDispatchResult.Dispatched(tapGesture(), callbackCompleted = true)
        assertThat(RecognizedTap.applyHelp(fresh, completed, 1_300L)).isTrue()
        assertThat(fresh.helpsUsedThisTurn).isEqualTo(1)
    }

    @Test
    fun unknownBoosterId_tapsActivate_knownTargetDoesNot() {
        val session = armed()
        session.noteEquippedBooster(null)
        assertThat(
            session.considerBoosterFrame(
                nowMs = 1_000L,
                activateVisible = true,
                barFull = true,
                canSendNow = true,
                needsTarget = true,
            ),
        ).isEqualTo(FiveMoveSession.BoosterStep.TAP)
        assertThat(session.boosterTargetNote).isEqualTo("id unknown")

        val channel = ScriptedChannel { gesture ->
            InputDispatchResult.Dispatched(gesture, callbackCompleted = true)
        }
        val sent = executor(channel).dispatchRecognizedTap(permit(tapGesture()))
        assertThat(channel.calls).isEqualTo(1)
        assertThat(applyBooster(session, sent, 1_100L)).isTrue()
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.SETTLING)

        val blocked = armed()
        blocked.noteEquippedBooster("the_jelly")
        assertThat(
            blocked.considerBoosterFrame(
                nowMs = 1_000L,
                activateVisible = true,
                barFull = true,
                canSendNow = true,
                needsTarget = true,
                provenNoTarget = false,
            ),
        ).isEqualTo(FiveMoveSession.BoosterStep.SWIPE)
        assertThat(blocked.boosterTargetNote).contains("needsTarget")
        assertThat(blocked.boosterTargetNote).doesNotContain("id unknown")
    }

    @Test
    fun helpTarget_skipsAnUnknownOrHiddenCell() {
        val colors = Array(Board.SIZE) { Array(Board.SIZE) { TileColor.UNKNOWN } }
        colors[2][2] = TileColor.B
        colors[2][3] = TileColor.B
        val pair = Board.fromColors(colors)
        assertThat(HelpTargets.adjacentToCluster(pair)).isNull()

        val open = pair.setCopy(2, 1, pair.get(2, 1).copy(color = TileColor.R, visible = true))
        val beside = HelpTargets.adjacentToCluster(open)
        assertThat(beside).isEqualTo(2 to 1)
        val chosen = open.get(beside!!.first, beside.second)
        assertThat(chosen.visible).isTrue()
        assertThat(chosen.color).isNotEqualTo(TileColor.UNKNOWN)

        val hidden = open.setCopy(2, 1, open.get(2, 1).copy(visible = false))
        assertThat(HelpTargets.adjacentToCluster(hidden)).isNull()
    }

    private fun gates(nowMs: Long, frameSequence: Long) = FiveMoveSession.Gates(
        nowMs = nowMs,
        a11yConnected = true,
        selfCheckMeasured = true,
        overlayCollapsed = true,
        overlayOutsideRoi = true,
        visionPass = true,
        frameFresh = true,
        ownUi = false,
        msSinceCollapse = FiveMoveSession.MIN_POST_COLLAPSE_MS,
        roiPlausible = true,
        frameSequence = frameSequence,
    )

    private fun pass(nowMs: Long, hash: Long, sequence: Long) = FiveMoveSession.SettleSample(
        nowMs = nowMs,
        boardHash = hash,
        diffFraction = 0.02f,
        frameFresh = true,
        roiPlausible = true,
        visionPass = true,
        unknownCount = 0,
        ownUi = false,
        a11yConnected = true,
        frameSequence = sequence,
        labelHash = hash,
        circlesBright = 10,
        circlesClassifiable = true,
    )
}
