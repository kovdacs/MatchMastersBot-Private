package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.overlay.OwnerStatus
import org.junit.Test

/**
 * Drives [FiveMoveSession] the way the live loop does: dispatch, gesture or
 * booster, settle frames, HUD, and outside touches. Each live stop is a full
 * script, not a single assertion.
 */
class PlaySessionReplayTest {
    @Test
    fun live0265_alternatingDiffs_playsThrough() {
        val play = Play()
        play.start()
        repeat(3) { index ->
            play.swipe(
                nextHash = 100L + index,
                diffs = floatArrayOf(0.45f, 0.02f),
            )
        }
        play.expectStillPlaying("0.26.5")
    }

    @Test
    fun live0266_slowCamera_playsThrough() {
        val play = Play()
        play.start()
        play.swipe(nextHash = 20L, cadenceMs = 1_000L, ages = longArrayOf(2_000L, 5_000L))
        play.swipe(nextHash = 21L, cadenceMs = 1_000L, ages = longArrayOf(3_000L, 4_000L))
        play.expectStillPlaying("0.26.6")
    }

    @Test
    fun live0267_cascadePastTwentySeconds_playsThrough() {
        val play = Play()
        play.start()
        play.swipe(nextHash = 30L, cascadeSeconds = 22)
        play.swipe(nextHash = 31L)
        play.expectStillPlaying("0.26.7")
    }

    @Test
    fun live0271_ownBoosterTap_isNotATouch() {
        val play = Play()
        play.start()
        play.swipe(nextHash = 40L)
        play.booster(circles = play.circles, sameBoard = false) { tap ->
            play.session.beginOwnGesture(220f, 924f, 220f, 924f, tap)
            assertThat(play.session.noteOutsideTouch(tap + 40L, 220f, 924f)).isNull()
            play.session.finishOwnGesture(tap + 80L)
            assertThat(play.session.noteOutsideTouch(tap + 200L, 220f, 924f)).isNull()
        }
        play.swipe(nextHash = 41L)
        assertThat(play.session.outsideTouches).isEqualTo(0)
        play.expectStillPlaying("0.27.1")
    }

    @Test
    fun live0272_boosterHudLossAndOutside_playsThrough() {
        val play = Play()
        play.start()
        play.swipe(nextHash = 50L)
        play.booster(circles = play.circles, sameBoard = false) {
            val unknown = play.session.notePlayHud(PlayGate.UNKNOWN, play.now)
            assertThat(unknown).isNotInstanceOf(FiveMoveSession.Decision.Stop::class.java)
            assertThat(play.session.notePlayHud(PlayGate.MENU, play.now + 1L))
                .isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
            assertThat(play.session.noteCircles(true, 0, true, play.now + 2L, 900L)).isNull()
            assertThat(play.session.noteOutsideTouch(play.now + 3L, 900f, 2_000f)).isNull()
        }
        play.swipe(nextHash = 51L)
        assertThat(play.session.outsideTouches).isEqualTo(0)
        play.expectStillPlaying("0.27.2")
    }

    @Test
    fun live0273_staleBoosterFrame_playsThrough() {
        val play = Play()
        play.start()
        play.swipe(nextHash = 60L)
        val limit = FiveMoveSession.SETTLE_FRAME_AGE_MS
        val stale = FreshFrameDispatch.booster(
            elapsedMs = 0L,
            ageMs = 5_150L,
            ageLimitMs = limit,
            gatesPass = true,
            showsActivateOrYourTurn = true,
            recheckAllow = false,
        )
        assertThat(stale).isEqualTo(FreshFrameDispatch.Booster.WAIT)
        val ready = FreshFrameDispatch.booster(
            elapsedMs = 1_000L,
            ageMs = 200L,
            ageLimitMs = limit,
            gatesPass = true,
            showsActivateOrYourTurn = true,
            recheckAllow = true,
        )
        assertThat(ready).isEqualTo(FreshFrameDispatch.Booster.SEND)
        assertThat(play.session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        play.booster(circles = play.circles, sameBoard = true)
        play.swipe(nextHash = 61L)
        play.expectStillPlaying("0.27.3")
    }

    @Test
    fun live0274_unchangedCirclesAfterBooster_playsThrough() {
        val play = Play()
        play.start()
        play.swipe(nextHash = 70L, dropCircle = false)
        val before = play.circles
        play.booster(circles = before, sameBoard = true)
        assertThat(play.circles).isEqualTo(before)
        val swipeAt = play.now
        play.swipe(nextHash = 71L)
        assertThat(play.lastSwipeAt - swipeAt).isLessThan(FiveMoveSession.BOOSTER_PLAYABLE_MS)
        play.expectStillPlaying("0.27.4")
    }

    @Test
    fun soloGame_tenCirclesThreeExtrasOneBooster_endsWhenMovesRunOut() {
        val play = Play()
        play.start(circles = 10)
        val plan = listOf(
            "swipe", "extra", "swipe", "booster", "extra", "swipe", "extra",
            "swipe", "swipe", "swipe", "swipe", "swipe", "swipe", "swipe",
        )
        var drops = 0
        var extras = 0
        var boosters = 0
        plan.forEach { kind ->
            when (kind) {
                "extra" -> {
                    play.swipe(nextHash = play.hash + 1, dropCircle = false)
                    extras += 1
                }
                "booster" -> {
                    val stayed = play.circles
                    play.booster(circles = stayed, sameBoard = true)
                    assertThat(play.circles).isEqualTo(stayed)
                    boosters += 1
                }
                else -> {
                    play.swipe(nextHash = play.hash + 1, dropCircle = true)
                    drops += 1
                }
            }
            if (play.circles > 0) {
                assertThat(play.session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
                assertThat(play.session.stopReason).isEmpty()
            }
        }
        assertThat(drops).isEqualTo(10)
        assertThat(extras).isEqualTo(3)
        assertThat(boosters).isEqualTo(1)
        assertThat(play.circles).isEqualTo(0)
        val spent = play.session.noteCircles(true, 0, true, play.now + 50L, play.seq + 1)
        assertThat(spent).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(play.session.stopReason).contains("moves spent")
        assertThat(OwnerStatus.hu(play.session.stopReason)).isEqualTo("Elfogytak a lépések")
    }

    @Test
    fun oneFpsFullGame_finishesUnderSixMinutes() {
        val play = Play()
        play.start(circles = 10)
        val start = play.now
        repeat(3) { play.swipeFast(dropCircle = false) }
        repeat(10) { play.swipeFast(dropCircle = true) }
        val elapsed = play.now - start
        assertThat(elapsed).isLessThan(6 * 60 * 1000L)
        assertThat(play.session.movesSnapshot().all { it.durationMs <= 8_000L }).isTrue()
        assertThat(play.circles).isEqualTo(0)
        val spent = play.session.noteCircles(true, 0, true, play.now + 50L, play.seq + 1)
        assertThat(spent).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(play.session.stopReason).contains("moves spent")
    }

    @Test
    fun boosterMiss_retriesTwice_thenLatchesWhenTheWordGoes() {
        val play = Play()
        play.start()
        play.swipe(nextHash = 80L)
        play.session.noteBoosterTap(170f, 935f, FiveMoveSession.BOOSTER_TAP_MS)
        assertThat(play.session.boosterMayTap()).isFalse()
        assertThat(play.session.noteBoosterBoard(activateVisible = true, barFull = true)).isEqualTo("watching")
        assertThat(play.session.boosterMayTap()).isFalse()
        assertThat(play.session.noteBoosterBoard(activateVisible = true, barFull = true)).isEqualTo("retry")
        assertThat(play.session.boosterMayTap()).isTrue()
        play.session.noteBoosterTap(171f, 936f, FiveMoveSession.BOOSTER_TAP_MS)
        assertThat(play.session.noteBoosterBoard(activateVisible = true, barFull = true)).isEqualTo("watching")
        assertThat(play.session.noteBoosterBoard(activateVisible = true, barFull = true)).isEqualTo("retry")
        play.session.noteBoosterTap(172f, 934f, FiveMoveSession.BOOSTER_TAP_MS)
        assertThat(play.session.noteBoosterBoard(activateVisible = false, barFull = false)).isEqualTo("rearmed")
        assertThat(play.session.boosterLatched).isFalse()
        assertThat(play.session.boosterAttempts).isEqualTo(0)
        assertThat(play.session.boosterMayTap()).isTrue()
        val log = play.session.boosterLog()
        assertThat(log).contains("point=(170,935)")
        assertThat(log).contains("durationMs=120")
        assertThat(log).contains("result=registered")
        play.swipe(nextHash = 81L)
        play.expectStillPlaying("booster retry")
    }

    @Test
    fun boosterStillVisibleAfterThreeTaps_isMissed_notLatched() {
        val play = Play()
        play.start()
        repeat(3) {
            play.session.noteBoosterTap(170f, 935f, FiveMoveSession.BOOSTER_TAP_MS)
            play.session.noteBoosterBoard(activateVisible = true, barFull = true)
            val second = play.session.noteBoosterBoard(activateVisible = true, barFull = true)
            if (it < 2) assertThat(second).isEqualTo("retry") else assertThat(second).isEqualTo("missed")
        }
        assertThat(play.session.boosterLatched).isFalse()
        assertThat(play.session.boosterGaveUp).isTrue()
        assertThat(play.session.boosterMayTap()).isFalse()
        assertThat(play.session.boosterLog()).contains("result=missed")
    }

    @Test(timeout = 2_000)
    fun activateStaysVisibleAndTapsIgnored_threeAttemptsThenSwipes() {
        val play = Play()
        play.start()
        val deadline = System.nanoTime() + 1_500_000_000L
        var taps = 0
        var swipes = 0
        var steps = 0
        var swipeSteps = 0
        while (taps < 3 || swipes < 1) {
            check(System.nanoTime() < deadline) { "booster handling hung" }
            steps += 1
            check(steps < 20) { "booster handling hung taps=$taps swipes=$swipes" }
            val step = play.session.considerBoosterFrame(
                nowMs = play.now,
                activateVisible = true,
                barFull = true,
                canSendNow = true,
                provenNoTarget = true,
            )
            play.now += FiveMoveSession.PLAYABLE_GAP_MS
            if (step == FiveMoveSession.BoosterStep.TAP) {
                check(taps < 3) { "fourth ACTIVATE tap" }
                play.session.noteBoosterTap(180f, 940f, FiveMoveSession.BOOSTER_TAP_MS)
                taps += 1
            } else {
                swipeSteps += 1
                if (play.session.boosterGaveUp) {
                    play.swipe(nextHash = play.hash + 1, dropCircle = false)
                    swipes += 1
                }
            }
        }
        assertThat(taps).isEqualTo(3)
        assertThat(swipeSteps).isAtLeast(1)
        assertThat(play.session.boosterAttempts).isEqualTo(3)
        assertThat(play.session.boosterGaveUp).isTrue()
        assertThat(play.session.boosterLatched).isFalse()
        assertThat(play.session.boosterMayTap()).isFalse()
        assertThat(play.session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(play.session.boosterLog()).contains("durationMs=120")
        assertThat(play.session.boosterLog()).contains("result=missed")
        play.expectStillPlaying("ignored activate")
    }

    @Test(timeout = 2_000)
    fun boosterBudget_givesUpAfterTenSecondsAndSwipes() {
        val play = Play()
        play.start()
        val held = play.session.considerBoosterFrame(
            nowMs = play.now,
            activateVisible = true,
            barFull = true,
            canSendNow = false,
            provenNoTarget = true,
        )
        assertThat(held).isEqualTo(FiveMoveSession.BoosterStep.SWIPE)
        assertThat(play.session.boosterAttempts).isEqualTo(0)
        play.now += FiveMoveSession.BOOSTER_HANDLING_BUDGET_MS
        val expired = play.session.considerBoosterFrame(
            nowMs = play.now,
            activateVisible = true,
            barFull = true,
            canSendNow = true,
            provenNoTarget = true,
        )
        assertThat(expired).isEqualTo(FiveMoveSession.BoosterStep.SWIPE)
        assertThat(play.session.boosterGaveUp).isTrue()
        assertThat(play.session.boosterMayTap()).isFalse()
        assertThat(play.session.boosterLog()).contains("result=not-sent")
        play.swipe(nextHash = play.hash + 1, dropCircle = false)
        play.expectStillPlaying("booster budget")
    }

    @Test(timeout = 2_000)
    fun boosterException_isLoggedAndPlayContinues() {
        val play = Play()
        play.start()
        play.session.noteBoosterException("planner blew up")
        assertThat(play.session.boosterLog()).contains("result=exception")
        assertThat(play.session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        play.swipe(nextHash = play.hash + 1, dropCircle = false)
        play.expectStillPlaying("booster exception")
    }

    @Test(timeout = 2_000)
    fun extraMove_circlesUnchanged_nextSwipeWithinTenSeconds_andIdleIsExported() {
        val play = Play()
        play.start(circles = 10)
        play.swipe(nextHash = 11L, dropCircle = true)
        play.swipe(nextHash = 12L, dropCircle = true)
        play.swipe(nextHash = 13L, dropCircle = false)
        assertThat(play.circles).isEqualTo(8)
        val opened = play.now
        val age = 3_500L
        val captured = opened + 4_000L
        val changed = play.session.considerSwipeFrame(age, 9_013L, captured + age, captured, rankMs = 4L)
        assertThat(changed).isNotNull()
        play.session.considerSwipeFrame(age, 13L, captured + age, captured, rankMs = 5L)
        val stable = play.session.considerSwipeFrame(age, 13L, captured + age + 300L, captured + 300L, rankMs = 5L)
        assertThat(stable).isNull()
        assertThat(captured + age - opened).isAtMost(FiveMoveSession.EXTRA_FOLLOW_UP_MS)
        assertThat(play.session.swipeOverride).isFalse()
        assertThat(play.session.report()).contains("idle reason=")
        assertThat(play.session.report()).contains("ageMs=$age")
        assertThat(play.session.report()).contains("rankMs=")
        play.now = captured + age
        play.swipe(nextHash = 14L, dropCircle = true)
        play.expectStillPlaying("extra-move follow-up")
    }

    @Test(timeout = 2_000)
    fun live0278_frameAgesOfOneToThreePointFiveSeconds_keepSwiping() {
        val play = Play()
        play.start(circles = 8)
        play.swipe(nextHash = 30L, dropCircle = true)
        val ages = longArrayOf(1_000L, 2_200L, 3_500L)
        var sent = 0
        var cursor = play.now
        while (cursor < play.now + 100_000L && sent < 3) {
            val age = ages[sent]
            cursor += 8_000L
            val captured = cursor - age
            val label = 40L + sent
            play.session.considerSwipeFrame(age, label, cursor, captured, rankMs = 2L)
            val second = play.session.considerSwipeFrame(age, label, cursor + 300L, captured + 300L, rankMs = 3L)
            check(second == null) { "age ${age}ms blocked: $second" }
            play.now = cursor + 400L
            play.swipe(nextHash = label, dropCircle = true)
            sent += 1
        }
        assertThat(sent).isEqualTo(3)
        assertThat(play.session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(play.session.swipesDispatched).isAtLeast(4)
        play.expectStillPlaying("0.27.8 idle")
    }

    @Test(timeout = 2_000)
    fun extraMove_matchingLabels_swipeDespiteAStaleAge() {
        val play = Play()
        play.start(circles = 8)
        play.swipe(nextHash = 21L, dropCircle = false)
        play.now += 3_000L
        val captured = play.now - 2_000L
        val block = play.session.considerSwipeFrame(2_000L, 21L, play.now, captured, rankMs = 1L)
        assertThat(block).isNull()
        assertThat(play.session.swipeOverride).isFalse()
    }

    @Test
    fun staleOrMovingFrame_isNotSwiped_andACellMismatchIsALabelError() {
        val play = Play()
        play.start()
        assertThat(play.session.considerSwipeFrame(400L, 1L)).contains("previous PASS")
        assertThat(play.session.considerSwipeFrame(6_000L, 1L)).contains("frame older than")
        assertThat(play.session.considerSwipeFrame(400L, 2L)).contains("labels changed")
        assertThat(play.session.considerSwipeFrame(3_500L, 2L)).isNull()
        val planned = LongArray(49) { 3L }
        val newest = planned.copyOf()
        newest[1] = 9L
        assertThat(SwipeGuard.cellsMatch(planned, newest, 0, 0, 0, 1)).isFalse()
        assertThat(SwipeGuard.cellsMatch(planned, planned, 0, 0, 0, 1)).isTrue()
        assertThat(SwipeGuard.unverifiedReason(6_000L, true, true)).isEqualTo("swipe made during board motion")
        assertThat(SwipeGuard.unverifiedReason(2_000L, true, true)).isEqualTo("label error")
        assertThat(SwipeGuard.unverifiedReason(200L, false, true)).isEqualTo("swipe made during board motion")
        assertThat(SwipeGuard.unverifiedReason(200L, true, false)).isEqualTo("swipe landed off-cell")
        assertThat(SwipeGuard.unverifiedReason(200L, true, true)).isEqualTo("label error")
    }

    private inner class Play {
        val session = FiveMoveSession()
        var now = 0L
        var seq = 1L
        var hash = 1L
        var circles = 10
        var lastSwipeAt = 0L

        fun start(circles: Int = 10) {
            this.circles = circles
            session.arm(0L)
            now = 1_000L
        }

        fun swipe(
            nextHash: Long,
            dropCircle: Boolean = true,
            diffs: FloatArray = floatArrayOf(0.45f, 0.02f),
            cadenceMs: Long = 0L,
            ages: LongArray = longArrayOf(100L, 100L),
            cascadeSeconds: Int = 0,
        ) {
            val beforeCircles = circles
            val afterCircles = if (dropCircle) (circles - 1).coerceAtLeast(0) else circles
            val plan = ++seq
            val go = session.requestDispatch(gates(now, plan))
            check(go is FiveMoveSession.Decision.Go) {
                "swipe refused at $now: $go stop=${session.stopReason}"
            }
            session.consumePermit(go.permit)
            val started = now
            lastSwipeAt = started
            session.noteGesture(
                gesture(
                    started = started,
                    beforeHash = hash,
                    circles = beforeCircles,
                    sequence = plan,
                ),
            )
            if (cascadeSeconds > 0) {
                for (second in 1..cascadeSeconds) {
                    now = started + second * 1_000L
                    settle(
                        board = 1_000L + second,
                        circles = beforeCircles,
                        diff = if (second % 2 == 0) 0.45f else 0.02f,
                        cadenceMs = 200L,
                        ageMs = 100L,
                    )
                    check(session.phase == FiveMoveSession.Phase.SETTLING) {
                        "cascade stopped at ${second}s: ${session.stopReason}"
                    }
                }
            }
            val post = FiveMoveSession.scaled(FiveMoveSession.POST_SWIPE_MS, cadenceMs)
            val gap = maxOf(FiveMoveSession.PLAYABLE_GAP_MS, cadenceMs)
            diffs.forEachIndexed { index, diff ->
                val age = ages.getOrElse(index) { ages.last() }
                val captured = if (cascadeSeconds > 0) {
                    now + post + index * gap
                } else {
                    started + post + index * gap
                }
                now = captured + age
                settle(nextHash, afterCircles, diff, cadenceMs, age)
            }
            check(session.phase == FiveMoveSession.Phase.RUNNING) {
                "move did not finish at $now stop=${session.stopReason} phase=${session.phase}"
            }
            hash = nextHash
            circles = afterCircles
            now += 200L
        }

        /** Two PASS frames one second apart. The first already differs, so there is no post-swipe wait. */
        fun swipeFast(dropCircle: Boolean) {
            val beforeCircles = circles
            val afterCircles = if (dropCircle) (circles - 1).coerceAtLeast(0) else circles
            val plan = ++seq
            val go = session.requestDispatch(gates(now, plan))
            check(go is FiveMoveSession.Decision.Go) {
                "fast swipe refused at $now: $go stop=${session.stopReason}"
            }
            session.consumePermit(go.permit)
            val started = now
            val next = hash + 1
            session.noteGesture(
                gesture(
                    started = started,
                    beforeHash = hash,
                    circles = beforeCircles,
                    sequence = plan,
                ),
            )
            now = started + 1_000L
            settle(next, afterCircles, 0.02f, 1_000L, 800L)
            now = started + 2_000L
            settle(next, afterCircles, 0.02f, 1_000L, 800L)
            check(session.phase == FiveMoveSession.Phase.RUNNING) {
                "fast move did not finish at $now stop=${session.stopReason} phase=${session.phase}"
            }
            val move = session.movesSnapshot().last()
            check(move.durationMs <= 8_000L) { "move took ${move.durationMs}ms" }
            hash = next
            circles = afterCircles
        }

        fun booster(circles: Int, sameBoard: Boolean, during: ((Long) -> Unit)? = null) {
            val tap = now
            val before = hash
            session.armBoosterSettle(
                x = 220f,
                y = 924f,
                nowMs = tap,
                playExport = "booster",
                swipeSequence = seq,
                beforeHash = before,
                beforeLabel = before,
                beforeCircles = circles,
            )
            during?.invoke(tap)
            val after = if (sameBoard) before else before + 17L
            now = tap + 400L
            settle(after, circles, 0.02f, 0L, 0L)
            now = tap + 800L
            settle(after, circles, 0.02f, 0L, 0L)
            check(session.phase == FiveMoveSession.Phase.RUNNING) {
                "booster did not resume stop=${session.stopReason}"
            }
            hash = after
            this.circles = circles
            now = tap + 900L
        }

        fun expectStillPlaying(label: String) {
            check(session.phase == FiveMoveSession.Phase.RUNNING) {
                "$label stopped: ${session.stopReason}"
            }
            check(session.stopReason.isEmpty()) { "$label stop=${session.stopReason}" }
        }

        private fun settle(board: Long, circles: Int, diff: Float, cadenceMs: Long, ageMs: Long) {
            val frame = ++seq
            val ageLimit = maxOf(FiveMoveSession.SETTLE_FRAME_AGE_MS, cadenceMs * 3L)
            session.onSettle(
                FiveMoveSession.SettleSample(
                    nowMs = now,
                    boardHash = board,
                    diffFraction = diff,
                    frameFresh = ageMs <= ageLimit,
                    roiPlausible = true,
                    visionPass = true,
                    unknownCount = 0,
                    ownUi = false,
                    a11yConnected = true,
                    frameSequence = frame,
                    labelHash = board,
                    frameAgeMs = ageMs,
                    cadenceMedianMs = cadenceMs,
                    circlesBright = circles,
                    circlesClassifiable = true,
                ),
            )
        }
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

    private fun gesture(started: Long, beforeHash: Long, circles: Int, sequence: Long) =
        FiveMoveSession.GestureFact(
            startedAtMs = started,
            nowMs = started + 50L,
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
            beforeCircles = circles,
            swipeSequence = sequence,
        )
}
