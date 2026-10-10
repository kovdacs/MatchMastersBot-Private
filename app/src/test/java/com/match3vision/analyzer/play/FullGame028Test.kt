package com.match3vision.analyzer.play

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.hud.HudObservation
import com.match3vision.analyzer.input.FiveMoveSession
import com.match3vision.analyzer.input.PlayGate
import com.match3vision.analyzer.moves.Move
import com.match3vision.analyzer.moves.PlayMoveRanker
import com.match3vision.analyzer.vision.CellVision
import com.match3vision.analyzer.vision.ColorShapeReconciler
import com.match3vision.analyzer.vision.SpecialDetector
import com.match3vision.analyzer.vision.SpecialType
import com.match3vision.analyzer.vision.TileColor
import com.match3vision.analyzer.vision.TileShape
import com.match3vision.analyzer.vision.VisionBoard
import java.io.File
import org.junit.After
import org.junit.Test

/**
 * 0.28 foundation. Each behavior is flagged. Live taps that need an owner
 * frame (equipped booster id, help buttons) are refused here.
 */
class FullGame028Test {
    @After
    fun resetFlags() {
        PlayFlags.reset()
    }

    @Test
    fun modes_soloTimerPvpAndUnknown() {
        val solo = HudObservation.solo(movesRemaining = 8)
        assertThat(PlayMode.classify(solo)).isEqualTo(PlayMode.SOLO)
        val timer = HudObservation.pvp(turnState = HudObservation.TURN_TIME, timeLeftSeconds = 12)
        assertThat(PlayMode.classify(timer)).isEqualTo(PlayMode.TIMER)
        val yours = HudObservation.pvp(turnState = HudObservation.TURN_YOUR, multiplier = 3)
        assertThat(PlayMode.classify(yours)).isEqualTo(PlayMode.PVP)
        val bannerBeforeTimer = HudObservation.pvp(
            turnState = HudObservation.TURN_OPPONENT,
            timeLeftSeconds = 9,
        )
        assertThat(PlayMode.classify(bannerBeforeTimer)).isEqualTo(PlayMode.PVP)
        val theirs = HudObservation.pvp(turnState = HudObservation.TURN_OPPONENT)
        assertThat(PlayMode.classify(theirs)).isEqualTo(PlayMode.PVP)
        assertThat(PlayMode.classify(HudObservation.UNKNOWN)).isEqualTo(PlayMode.BASIC)
        PlayFlags.modeAdapt = false
        assertThat(PlayMode.classify(solo)).isEqualTo(PlayMode.BASIC)
    }

    @Test
    fun pvp_waitsThroughOpponentTurn_thenResumes() {
        val session = FiveMoveSession()
        session.arm(0L)
        repeat(FiveMoveSession.MODE_CONFIRM_FRAMES) {
            session.observeHud(HudObservation.pvp(turnState = HudObservation.TURN_OPPONENT))
        }
        val held = session.notePlayHud(PlayGate.OPPONENT, 1_000L)
        assertThat(held).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        val blocked = session.requestDispatch(gates(2_000L, 1L))
        assertThat(blocked).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat((blocked as FiveMoveSession.Decision.Hold).reason).isEqualTo("opponent turn")
        repeat(FiveMoveSession.MODE_CONFIRM_FRAMES) {
            session.observeHud(HudObservation.pvp(turnState = HudObservation.TURN_YOUR, multiplier = 2))
        }
        assertThat(session.notePlayHud(PlayGate.OURS, 3_000L)).isNull()
        val go = session.requestDispatch(gates(4_000L, 2L))
        assertThat(go).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        assertThat(session.report()).contains("playMode=PVP")
        assertThat(session.report()).contains("multiplier=2")
        assertThat(session.report()).contains("idle reason=opponent turn")
    }

    @Test
    fun timer_playsWithZeroCircles_andStopsWhenTimeHitsZero() {
        val session = FiveMoveSession()
        session.arm(0L)
        val ticking = HudObservation.pvp(turnState = HudObservation.TURN_TIME, timeLeftSeconds = 12)
        session.observeHud(ticking)
        assertThat(session.screenMode).isEqualTo(PlayMode.SOLO)
        repeat(FiveMoveSession.MODE_CONFIRM_FRAMES - 1) { session.observeHud(ticking) }
        assertThat(session.screenMode).isEqualTo(PlayMode.TIMER)
        assertThat(session.noteCircles(true, 0, true, 1_000L, 1L)).isNull()
        assertThat(session.noteCircles(true, 0, true, 1_100L, 2L)).isNull()
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        val done = HudObservation.pvp(turnState = HudObservation.TURN_TIME, timeLeftSeconds = 0)
        session.observeHud(done)
        assertThat(session.notePlayHud(PlayGate.OURS, 2_000L)).isNull()
        session.observeHud(done)
        val stop = session.notePlayHud(PlayGate.OURS, 2_100L)
        assertThat(stop).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(session.stopReason).contains("time out")
    }

    @Test
    fun unknownLayout_doesNotStop_andSoloOpponentStillDoes() {
        val unknown = FiveMoveSession()
        unknown.arm(0L)
        repeat(FiveMoveSession.MODE_CONFIRM_FRAMES) { unknown.observeHud(HudObservation.UNKNOWN) }
        assertThat(unknown.screenMode).isEqualTo(PlayMode.BASIC)
        assertThat(unknown.notePlayHud(PlayGate.UNKNOWN, 1_000L)).isNull()
        assertThat(unknown.notePlayHud(PlayGate.OPPONENT, 1_100L)).isNull()
        val held = unknown.requestDispatch(
            FiveMoveSession.Gates(
                nowMs = 1_200L,
                a11yConnected = true,
                selfCheckMeasured = true,
                overlayCollapsed = true,
                overlayOutsideRoi = true,
                visionPass = true,
                frameFresh = true,
                ownUi = false,
                msSinceCollapse = FiveMoveSession.MIN_POST_COLLAPSE_MS,
                roiPlausible = true,
                frameSequence = 3L,
            ),
        )
        assertThat(held).isInstanceOf(FiveMoveSession.Decision.Hold::class.java)
        assertThat((held as FiveMoveSession.Decision.Hold).reason).isEqualTo("turn unconfirmed")
        assertThat(unknown.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        val solo = FiveMoveSession()
        solo.arm(0L)
        solo.observeHud(HudObservation.solo(movesRemaining = 8))
        val stop = solo.notePlayHud(PlayGate.OPPONENT, 1_000L)
        assertThat(stop).isInstanceOf(FiveMoveSession.Decision.Stop::class.java)
        assertThat(solo.stopReason).contains("Opponent's Turn")
    }

    @Test
    fun registry_noTargetAndTarget_useActivateTap() {
        val registry = BoosterRegistry.parse(asset("booster_registry.json").readText())
        val firecracker = registry.get("firecracker")
        assertThat(firecracker).isNotNull()
        assertThat(firecracker!!.needsTarget).isFalse()
        assertThat(firecracker.activation).isEqualTo(BoosterRegistry.ACTIVATION)
        assertThat(firecracker.targetType).isEqualTo("none")
        val jelly = registry.get("the_jelly")!!
        assertThat(jelly.needsTarget).isTrue()
        assertThat(jelly.targetType).isEqualTo("cell")
        assertThat(jelly.activation).isEqualTo(BoosterRegistry.ACTIVATION)
        val wand = registry.get("magic_wand")!!
        assertThat(wand.targetType).isEqualTo("color")
        val helps = BoosterRegistry.parse(asset("help_registry.json").readText())
        assertThat(helps.get("hammer")!!.needsTarget).isTrue()
        assertThat(helps.get("shuffle")!!.needsTarget).isFalse()
    }

    @Test
    fun targetBooster_picksTheBluestCell_andUnknownIdDoesNotTap() {
        val colors = Array(Board.SIZE) { Array(Board.SIZE) { TileColor.R } }
        colors[3][4] = TileColor.B
        val board = Board.fromColors(colors)
        val picked = TargetPicker().best(board, "cell")
        assertThat(picked.row).isEqualTo(3)
        assertThat(picked.col).isEqualTo(4)
        assertThat(picked.blue).isGreaterThan(0)
        val registry = BoosterRegistry.parse(asset("booster_registry.json").readText())
        val session = FiveMoveSession()
        session.arm(0L)
        assertThat(session.planBoosterTarget(registry, board)).isNull()
        assertThat(session.boosterTargetNote).contains("off")
        PlayFlags.targetBoosters = true
        assertThat(session.planBoosterTarget(registry, board)).isNull()
        assertThat(session.boosterTargetNote).contains("unverified")
        session.noteEquippedBooster("firecracker")
        assertThat(session.planBoosterTarget(registry, board)).isNull()
        assertThat(session.boosterTargetNote).contains("target=none")
        session.noteEquippedBooster("the_jelly")
        val target = session.planBoosterTarget(registry, board)
        assertThat(target).isNotNull()
        assertThat(target!!.row).isEqualTo(3)
        assertThat(target.col).isEqualTo(4)
        assertThat(session.considerHelp(hasLegalMove = false, extraMoveAvailable = true)).isNull()
        assertThat(session.helpNote).contains("unread")
        assertThat(
            session.considerBoosterFrame(
                nowMs = 1_000L,
                activateVisible = true,
                barFull = true,
                canSendNow = true,
                needsTarget = true,
                provenNoTarget = false,
            ),
        ).isEqualTo(FiveMoveSession.BoosterStep.SWIPE)
        assertThat(session.boosterTargetNote).contains("blocked")
        session.noteEquippedBooster(null)
        assertThat(
            session.considerBoosterFrame(
                nowMs = 1_100L,
                activateVisible = true,
                barFull = true,
                canSendNow = true,
            ),
        ).isEqualTo(FiveMoveSession.BoosterStep.TAP)
        assertThat(session.boosterTargetNote).isEqualTo("id unknown")
    }

    @Test
    fun helps_onePerTurn_andUnreadChargesDoNotTap() {
        val unread = HelpPolicy.Charges(hammer = null, shuffle = null, geometryVerified = false)
        assertThat(HelpPolicy.choose(false, true, unread, 0)).isNull()
        val charged = HelpPolicy.Charges(hammer = 1, shuffle = 0, geometryVerified = true)
        val stuck = HelpPolicy.choose(false, false, charged, 0)
        assertThat(stuck!!.id).isEqualTo("hammer")
        assertThat(stuck.reason).isEqualTo("no 3-match")
        val extra = HelpPolicy.choose(true, true, charged, 0)
        assertThat(extra!!.reason).isEqualTo("enables extra move")
        assertThat(HelpPolicy.choose(false, true, charged, HelpPolicy.MAX_PER_TURN)).isNull()
        PlayFlags.helps = false
        assertThat(HelpPolicy.choose(false, true, charged, 0)).isNull()
    }

    @Test
    fun specialLabels_andAxisReachTheSimulatorTile() {
        assertThat(SpecialLabels.label(SpecialType.TWO_WAY_ARROW, SpecialLabels.AXIS_ROW)).isEqualTo("arrow-row")
        assertThat(SpecialLabels.label(SpecialType.TWO_WAY_ARROW, SpecialLabels.AXIS_COL)).isEqualTo("arrow-col")
        assertThat(SpecialLabels.label(SpecialType.TWO_WAY_ARROW)).isEqualTo("arrow")
        assertThat(SpecialLabels.label(SpecialType.BOMB)).isEqualTo("bomb")
        assertThat(SpecialLabels.label(SpecialType.LIGHTNING)).isEqualTo("color-bomb")
        val reconciled = ColorShapeReconciler.reconcile(
            color = TileColor.R,
            colorConf = 0.9f,
            shape = TileShape.CIRCLE,
            shapeConf = 0.9f,
            special = SpecialType.TWO_WAY_ARROW,
            specialAxis = SpecialDetector.AXIS_COL,
        )
        assertThat(reconciled.specialAxis).isEqualTo(SpecialDetector.AXIS_COL)
        val cells = Array(Board.SIZE) { Array(Board.SIZE) { plain() } }
        cells[1][2] = plain().copy(special = SpecialType.TWO_WAY_ARROW, specialAxis = SpecialDetector.AXIS_COL)
        val tile = Board.fromVision(VisionBoard(cells)).get(1, 2)
        assertThat(tile.starValue).isEqualTo(SpecialDetector.AXIS_COL)
        assertThat(SpecialLabels.label(tile.special, tile.starValue)).isEqualTo("arrow-col")
    }

    @Test
    fun combo_neverOutranksAnExtraMove_andSpecialsDefaultOff() {
        assertThat(PlayFlags.specials).isFalse()
        var board = latinBoard()
        board = board.setCopy(0, 0, board.get(0, 0).copy(color = TileColor.R, shape = TileShape.CIRCLE))
        board = board.setCopy(0, 1, board.get(0, 1).copy(color = TileColor.R, shape = TileShape.CIRCLE))
        board = board.setCopy(0, 2, board.get(0, 2).copy(color = TileColor.R, shape = TileShape.CIRCLE))
        board = board.setCopy(0, 3, board.get(0, 3).copy(color = TileColor.B, shape = TileShape.STAR))
        board = board.setCopy(1, 3, board.get(1, 3).copy(color = TileColor.R, shape = TileShape.CIRCLE))
        board = board.setCopy(6, 0, board.get(6, 0).copy(special = SpecialType.BOMB))
        board = board.setCopy(6, 1, board.get(6, 1).copy(special = SpecialType.LIGHTNING))
        val plain = PlayMoveRanker().rankForPlay(board)
        assertThat(plain.ordered.map { it.move.normalized() }).doesNotContain(Move(6, 0, 6, 1).normalized())
        PlayFlags.specials = true
        val ranked = PlayMoveRanker().rankForPlay(board)
        val firstExtra = ranked.ordered.indexOfFirst { it.extraMove }
        val firstCombo = ranked.ordered.indexOfFirst { it.combo && !it.extraMove }
        assertThat(firstExtra).isAtLeast(0)
        if (firstCombo >= 0) assertThat(firstExtra).isLessThan(firstCombo)
        val row = board.get(2, 2)
        val spun = board.setCopy(2, 2, row.copy(special = SpecialType.TWO_WAY_ARROW, starValue = 1))
        val col = board.setCopy(2, 2, row.copy(special = SpecialType.TWO_WAY_ARROW, starValue = 2))
        assertThat(spun.labelHash()).isEqualTo(col.labelHash())
        assertThat(spun.labelKeys().contentEquals(col.labelKeys())).isTrue()
    }

    @Test
    fun opponentWait_clearsAfterTwentySecondsWithoutOpponentFrames() {
        val session = FiveMoveSession()
        session.arm(0L)
        val opponent = HudObservation.pvp(turnState = HudObservation.TURN_OPPONENT)
        repeat(FiveMoveSession.MODE_CONFIRM_FRAMES) { session.observeHud(opponent) }
        session.notePlayHud(PlayGate.OPPONENT, 1_000L)
        val waiting = session.requestDispatch(gates(2_000L, 1L))
        assertThat((waiting as FiveMoveSession.Decision.Hold).reason).isEqualTo("opponent turn")
        session.notePlayHud(PlayGate.UNKNOWN, 1_000L + FiveMoveSession.IDLE_WITHOUT_GESTURE_MS)
        val unconfirmed = session.requestDispatch(gates(22_000L, 2L))
        assertThat((unconfirmed as FiveMoveSession.Decision.Hold).reason).isEqualTo("turn unconfirmed")
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
        session.notePlayHud(PlayGate.OURS, 23_000L)
        val go = session.requestDispatch(gates(24_000L, 3L))
        assertThat(go).isInstanceOf(FiveMoveSession.Decision.Go::class.java)
    }

    @Test
    fun boosterFlagOff_doesNotTap() {
        val session = FiveMoveSession()
        session.arm(0L)
        PlayFlags.boosters = false
        assertThat(
            session.considerBoosterFrame(
                nowMs = 1_000L,
                activateVisible = true,
                barFull = true,
                canSendNow = true,
            ),
        ).isEqualTo(FiveMoveSession.BoosterStep.SWIPE)
    }

    private fun plain() = CellVision(
        color = TileColor.R,
        shape = TileShape.CIRCLE,
        special = SpecialType.NONE,
        occluded = false,
        confidence = 1f,
        isUnknown = false,
    )

    private fun latinBoard(): Board {
        val palette = listOf(
            TileColor.R, TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O,
        )
        val colors = Array(Board.SIZE) { row -> Array(Board.SIZE) { col -> palette[(row + col) % 6] } }
        return Board.fromColors(colors)
    }

    private fun asset(name: String): File =
        listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
            .firstOrNull { it.exists() }
            ?: error("$name not found")

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
}
