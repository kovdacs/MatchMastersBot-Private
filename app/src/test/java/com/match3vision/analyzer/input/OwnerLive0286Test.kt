package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ScreenMeasurement
import com.match3vision.analyzer.hud.HudObservation
import com.match3vision.analyzer.play.HelpPolicy
import com.match3vision.analyzer.play.PlayFlags
import org.junit.After
import org.junit.Test

/**
 * Live 0.28.5 solo: help taps failed with "input disabled (live)" and the
 * tick returned before any swipe. Circles stuck at 0/10 stopped a fresh game.
 * One opponent-colored frame stopped a solo verification.
 */
class OwnerLive0286Test {

    @After
    fun resetFlags() {
        PlayFlags.reset()
    }

    @Test
    fun helps_defaultOff_andResetStaysOff() {
        assertThat(PlayFlags.helps).isFalse()
        PlayFlags.helps = true
        PlayFlags.reset()
        assertThat(PlayFlags.helps).isFalse()
        val session = FiveMoveSession()
        session.arm(0L)
        val charges = HelpPolicy.Charges(hammer = 1, shuffle = 1, geometryVerified = true)
        assertThat(
            session.considerHelp(
                hasLegalMove = false,
                extraMoveAvailable = true,
                charges = charges,
            ),
        ).isNull()
        assertThat(session.helpNote).contains("helps off")
        assertThat(
            session.advanceHelp(
                nowMs = 1_000L,
                promptVisible = false,
                playable = true,
                choice = HelpPolicy.Choice("hammer", needsTarget = true, reason = "enables extra move"),
                targetX = 400f,
                targetY = 1500f,
            ),
        ).isNull()
    }

    @Test
    fun helpAttempt_isLimitedToOnePer30s() {
        PlayFlags.helps = true
        val session = armed()
        val choice = HelpPolicy.Choice("hammer", needsTarget = true, reason = "enables extra move")
        val first = session.advanceHelp(1_000L, false, true, choice, 400f, 1500f)
        assertThat(first).isInstanceOf(FiveMoveSession.HelpGesture.Tap::class.java)
        session.abandonHelp(1_100L, "help tap not sent")
        assertThat(session.helpConsumesTurn()).isFalse()
        val blocked = session.advanceHelp(1_200L, false, true, choice, 400f, 1500f)
        assertThat(blocked).isNull()
        assertThat(session.helpNote).isEqualTo("help cooldown")
        val go = session.requestDispatch(gates(1_300L, frameSequence = 2L))
        assertThat(go).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
        val later = session.advanceHelp(
            1_000L + FiveMoveSession.HELP_ATTEMPT_GAP_MS,
            false,
            true,
            choice,
            400f,
            1500f,
        )
        assertThat(later).isInstanceOf(FiveMoveSession.HelpGesture.Tap::class.java)
    }

    @Test
    fun switchOff_tapIsInputDisabled_whileInputEnabledReachesTheChannel() {
        val switch = InputEnableSwitch.disabledByDefault()
        val channel = ScriptedChannel { gesture ->
            InputDispatchResult.Dispatched(gesture, callbackCompleted = true)
        }
        val executor = executor(channel, inputEnabled = { switch.isEnabled() })
        val blocked = executor.dispatchRecognizedTap(permit(tapGesture()))
        assertThat(channel.calls).isEqualTo(0)
        assertThat((blocked as InputDispatchResult.Failed).reason).contains("input disabled (live)")

        val sent = RecognizedTapDispatch.whileInputEnabled(switch) {
            assertThat(switch.isEnabled()).isTrue()
            executor.dispatchRecognizedTap(permit(tapGesture()))
        }
        assertThat(switch.isEnabled()).isFalse()
        assertThat(channel.calls).isEqualTo(1)
        assertThat(sent).isInstanceOf(InputDispatchResult.Dispatched::class.java)
        assertThat((sent as InputDispatchResult.Dispatched).callbackCompleted).isTrue()
    }

    @Test
    fun helpBlocked_andActivateVisible_sendsTheTapThenSwipes() {
        PlayFlags.helps = true
        PlayFlags.boosters = true
        val session = armed()
        val choice = HelpPolicy.Choice("hammer", needsTarget = true, reason = "enables extra move")
        val help = session.advanceHelp(1_000L, false, true, choice, 400f, 1500f)
        assertThat(help).isInstanceOf(FiveMoveSession.HelpGesture.Tap::class.java)

        val switch = InputEnableSwitch.disabledByDefault()
        val channel = ScriptedChannel { gesture ->
            InputDispatchResult.Dispatched(gesture, callbackCompleted = true)
        }
        val executor = executor(channel, inputEnabled = { switch.isEnabled() })
        val blocked = executor.dispatchRecognizedTap(permit(tapGesture()))
        assertThat(RecognizedTap.applyHelp(session, blocked, 1_100L)).isFalse()
        assertThat(session.helpsUsedThisTurn).isEqualTo(0)
        assertThat(session.helpConsumesTurn()).isFalse()
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)

        assertThat(
            session.considerBoosterFrame(
                nowMs = 1_200L,
                activateVisible = true,
                barFull = true,
                canSendNow = true,
            ),
        ).isEqualTo(FiveMoveSession.BoosterStep.TAP)
        assertThat(session.boosterTargetNote).isEqualTo("id unknown")

        val sent = RecognizedTapDispatch.whileInputEnabled(switch) {
            executor.dispatchRecognizedTap(permit(tapGesture()))
        }
        assertThat(channel.calls).isEqualTo(1)
        assertThat(
            RecognizedTap.applyBooster(
                session = session,
                result = sent,
                x = 180f,
                y = 940f,
                nowMs = 1_300L,
                playExport = "booster",
                swipeSequence = 1L,
                beforeHash = 7L,
                beforeLabel = 7L,
                beforeCircles = 10,
            ),
        ).isTrue()
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.SETTLING)
        session.onSettle(pass(nowMs = 1_700L, hash = 7L, sequence = 2L))
        session.onSettle(pass(nowMs = 2_100L, hash = 7L, sequence = 3L))
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        val swipe = session.requestDispatch(gates(2_200L, frameSequence = 4L))
        assertThat(swipe).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
    }

    @Test
    fun failedHelpAndFailedBooster_stillRequestASwipe() {
        PlayFlags.helps = true
        val session = armed()
        val choice = HelpPolicy.Choice("hammer", needsTarget = true, reason = "enables extra move")
        session.advanceHelp(1_000L, false, true, choice, 400f, 1500f)
        val failed = InputDispatchResult.Failed(
            "TOCTOU recheck blocked dispatchGesture: input disabled (live)",
        )
        assertThat(RecognizedTap.applyHelp(session, failed, 1_100L)).isFalse()
        assertThat(session.helpConsumesTurn()).isFalse()
        assertThat(
            session.considerBoosterFrame(
                nowMs = 1_200L,
                activateVisible = true,
                barFull = true,
                canSendNow = true,
            ),
        ).isEqualTo(FiveMoveSession.BoosterStep.TAP)
        assertThat(
            RecognizedTap.applyBooster(
                session = session,
                result = failed,
                x = 180f,
                y = 940f,
                nowMs = 1_300L,
                playExport = "booster",
                swipeSequence = 1L,
                beforeHash = 7L,
                beforeLabel = 7L,
                beforeCircles = 10,
            ),
        ).isFalse()
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(session.boosterAttempts).isEqualTo(0)
        val swipe = session.requestDispatch(gates(1_400L, frameSequence = 2L))
        assertThat(swipe).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
    }

    @Test
    fun circlesStuckAtZero_doNotStop_untilTheyChangeAfterASwipe() {
        val fresh = FiveMoveSession()
        fresh.arm(0L)
        assertThat(fresh.noteCircles(true, 0, true, 1_000L, 1L)).isNull()
        assertThat(fresh.noteCircles(true, 0, true, 2_000L, 2L)).isNull()
        assertThat(fresh.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)

        val stuck = FiveMoveSession()
        stuck.arm(0L)
        assertThat(stuck.noteCircles(true, 0, true, 1_000L, 1L)).isNull()
        swipe(stuck)
        assertThat(stuck.noteCircles(true, 0, true, 2_000L, 2L)).isNull()
        assertThat(stuck.noteCircles(true, 0, true, 3_000L, 3L)).isNull()
        assertThat(stuck.phase).isNotEqualTo(FiveMoveSession.Phase.STOPPED)

        val spent = FiveMoveSession()
        spent.arm(0L)
        assertThat(spent.noteCircles(true, 10, true, 1_000L, 1L)).isNull()
        swipe(spent)
        assertThat(spent.noteCircles(true, 0, true, 2_000L, 2L)).isNull()
        val stop = spent.noteCircles(true, 0, true, 3_000L, 3L)
        assertThat(stop).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat((stop as FiveMoveSession.Decision.Stop).reason).contains("moves spent")
    }

    @Test
    fun solo_stopsOnOpponentTurnOnlyAfterThreeBars() {
        val solo = FiveMoveSession()
        solo.arm(0L)
        solo.observeHud(HudObservation.solo(movesRemaining = 10))
        repeat(2) { index ->
            solo.observeHud(HudObservation.pvp(turnState = HudObservation.TURN_OPPONENT))
            val decision = solo.notePlayHud(PlayGate.OPPONENT, 1_000L + index)
            assertThat(decision).isNull()
            assertThat(solo.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        }
        solo.observeHud(HudObservation.pvp(turnState = HudObservation.TURN_OPPONENT))
        val stopped = solo.notePlayHud(PlayGate.OPPONENT, 3_000L)
        assertThat(stopped).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat((stopped as FiveMoveSession.Decision.Stop).reason).contains("Opponent's Turn")

        val circlesOnly = FiveMoveSession()
        circlesOnly.arm(0L)
        circlesOnly.observeHud(
            HudObservation(circlesClassifiable = true, circlesBright = 0, soloPositive = false),
        )
        circlesOnly.observeHud(HudObservation.pvp(turnState = HudObservation.TURN_OPPONENT))
        assertThat(circlesOnly.notePlayHud(PlayGate.OPPONENT, 1_000L)).isNull()

        val pvp = FiveMoveSession()
        pvp.arm(0L)
        pvp.observeHud(HudObservation.pvp(turnState = HudObservation.TURN_OPPONENT))
        val waiting = pvp.notePlayHud(PlayGate.OPPONENT, 1_000L)
        assertThat(waiting).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat((waiting as FiveMoveSession.Decision.Hold).reason).contains("opponent turn")
    }

    @Test
    fun soloVerification_needsThreeOpponentFrames() {
        val session = FiveMoveSession()
        session.arm(0L)
        session.observeHud(HudObservation.solo(movesRemaining = 10))
        val permit = (session.requestDispatch(gates(1_000L, 1L)) as FiveMoveSession.Decision.Go).permit
        assertThat(session.consumePermit(permit)).isTrue()
        session.noteGesture(
            FiveMoveSession.GestureFact(
                startedAtMs = 1_000L,
                nowMs = 1_100L,
                callbackCompleted = true,
                cancelled = false,
                cells = "0,0↔0,1",
                fromX = 1f,
                fromY = 1f,
                toX = 2f,
                toY = 1f,
                beforeHash = 5L,
                beforeUnknown = 0,
            ),
        )
        val first = session.onSettle(opponentSample(1_800L))
        val second = session.onSettle(opponentSample(2_000L))
        assertThat(first).isNotInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(second).isNotInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        val third = session.onSettle(opponentSample(2_200L))
        assertThat(third).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat((third as FiveMoveSession.Decision.Stop).reason)
            .contains("opponent turn during verification")
    }

    private fun armed(): FiveMoveSession {
        val session = FiveMoveSession()
        session.arm(0L)
        return session
    }

    private fun swipe(session: FiveMoveSession) {
        val permit = (session.requestDispatch(gates(1_100L, 8L)) as FiveMoveSession.Decision.Go).permit
        assertThat(session.consumePermit(permit)).isTrue()
        session.noteGesture(
            FiveMoveSession.GestureFact(
                startedAtMs = 1_100L,
                nowMs = 1_200L,
                callbackCompleted = true,
                cancelled = false,
                cells = "0,0↔0,1",
                fromX = 1f,
                fromY = 1f,
                toX = 2f,
                toY = 1f,
                beforeHash = 5L,
                beforeUnknown = 0,
            ),
        )
    }

    private fun opponentSample(nowMs: Long) = FiveMoveSession.SettleSample(
        nowMs = nowMs,
        boardHash = 5L,
        diffFraction = 0f,
        frameFresh = true,
        roiPlausible = true,
        visionPass = false,
        unknownCount = 0,
        ownUi = false,
        a11yConnected = true,
        countBoardChange = false,
        frameSequence = nowMs,
    )

    private class ScriptedChannel(
        private val answer: (GestureSpec) -> InputDispatchResult,
    ) : AccessibilityGestureChannel {
        var calls: Int = 0
        override fun canDispatchGestures(): Boolean = true
        override fun diagnose(): String = "scripted"
        override fun dispatchGesture(
            gesture: GestureSpec,
            awaitCompletion: Boolean,
            timeoutMs: Long,
        ): InputDispatchResult {
            calls += 1
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
        inputEnabled: () -> Boolean,
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
                inputEnabled = { inputEnabled() },
                stopped = { false },
                captureReady = { true },
                screen = { screen },
            ),
        )
    }

    private fun tapGesture() = GestureSpec.tap(180f, 940f, FiveMoveSession.BOOSTER_TAP_MS)

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
