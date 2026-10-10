package com.match3vision.analyzer.input

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.hud.HudObservation
import com.match3vision.analyzer.moves.PlayMoveRanker
import com.match3vision.analyzer.play.BoosterRegistry
import com.match3vision.analyzer.play.ContinuousPlay
import com.match3vision.analyzer.play.HelpPolicy
import com.match3vision.analyzer.play.PlayFlags
import com.match3vision.analyzer.play.PlayMode
import com.match3vision.analyzer.play.TargetPicker
import kotlin.math.hypot

/**
 * Plays while bright move circles or timer seconds remain. A hard cap stops
 * the session at [MAX_MOVES] gestures or [SESSION_LIMIT_MS].
 *
 * Move 1 is a probe. Later moves are issued only after the previous one verifies.
 * The same checks apply to every move. A failed check stops the session.
 * The same move is not retried.
 *
 * The clock starts at [arm] (the 10 LÉPÉS TESZT press, after the self-check).
 * All verified moves must finish inside [SESSION_LIMIT_MS].
 * A swipe is never abandoned at 20 s. The next swipe waits for a playable
 * board: two vision PASS frames whose known labels differ in at most one
 * cell, at least [PLAYABLE_GAP_MS] apart. There is no fixed wait after the
 * swipe. The gap uses capture time and does not grow with a slow camera.
 * Frame age up to [SETTLE_FRAME_AGE_MS] is accepted. If a swipe is still not
 * playable after [SETTLE_WAIT_MS],
 * the session stops. An ACTIVATE tap does not: the first two agreeing PASS
 * frames are playable even when the labels and the HUD did not change, and
 * after [BOOSTER_PLAYABLE_MS] without that pair play continues.
 *
 * This type does not run vision and does not dispatch a gesture.
 */
class FiveMoveSession {
    enum class Phase { IDLE, RUNNING, SETTLING, STOPPED }

    /** One playable board. [TAP] is a single ACTIVATE tap. [SWIPE] must not wait. */
    enum class BoosterStep { TAP, SWIPE }

    data class Gates(
        val nowMs: Long,
        val a11yConnected: Boolean,
        val selfCheckMeasured: Boolean,
        val overlayCollapsed: Boolean,
        val overlayOutsideRoi: Boolean,
        val visionPass: Boolean,
        val frameFresh: Boolean,
        val ownUi: Boolean,
        val msSinceCollapse: Long,
        val roiPlausible: Boolean,
        /** 0 means the caller did not supply a sequence. */
        val frameSequence: Long = 0L,
    )

    data class Permit(val token: Long, val moveNumber: Int)

    data class GestureFact(
        val startedAtMs: Long,
        val nowMs: Long,
        val callbackCompleted: Boolean,
        val cancelled: Boolean,
        val cells: String,
        val fromX: Float,
        val fromY: Float,
        val toX: Float,
        val toY: Float,
        val beforeHash: Long,
        val beforeUnknown: Int,
        val playExport: String = "",
        val matchLen: Int = 0,
        val extraMove: Boolean = false,
        val blueCleared: Int = 0,
        val totalCleared: Int = 0,
        val playUncertain: Boolean = false,
        /** Sequence of the frame the swipe was planned on. */
        val swipeSequence: Long = 0L,
        /** Labels with specials ignored. Defaults to [beforeHash] for older callers. */
        val beforeLabel: Long = beforeHash,
        val beforeCircles: Int? = null,
        /** 4+ clear or a special. The post-swipe wait is longer. */
        val longSettle: Boolean = false,
        val frameAgeMs: Long = 0L,
        val labelsMatchedPrevious: Boolean = true,
        val onCellCenter: Boolean = true,
    )

    data class SettleSample(
        val nowMs: Long,
        val boardHash: Long,
        val diffFraction: Float?,
        val frameFresh: Boolean,
        val roiPlausible: Boolean,
        val visionPass: Boolean,
        val unknownCount: Int,
        val ownUi: Boolean,
        val a11yConnected: Boolean,
        val overlayOutside: Boolean = true,
        val capturedAfterGesture: Boolean = true,
        val frameSequence: Long = 0L,
        /** False when this frame is the opponent's turn. That change is not verified. */
        val countBoardChange: Boolean = true,
        /**
         * Move 1 of an auto-calibration probe. False means the changed cells
         * missed the swapped rows and columns.
         */
        val swapOverlaps: Boolean = true,
        /** Null uses [boardHash], so older tests still treat a new hash as new labels. */
        val labelHash: Long? = null,
        /** Per-cell keys. When set, two frames agree if at most one known cell differs. */
        val labelKeys: LongArray? = null,
        val frameAgeMs: Long = 0L,
        val cadenceMedianMs: Long = 0L,
        val circlesBright: Int? = null,
        val circlesClassifiable: Boolean = false,
        /** Level-end or shuffle dim. Not a playable board and not a menu. */
        val dimmed: Boolean = false,
    )

    data class MoveRecord(
        val number: Int,
        val cells: String,
        val fromX: Float,
        val fromY: Float,
        val toX: Float,
        val toY: Float,
        val callback: String,
        val verification: String,
        val beforeUnknown: Int,
        val afterUnknown: Int,
        val startedAtMs: Long,
        val finishedAtMs: Long,
        val durationMs: Long,
        val ignoredTransient: Int = 0,
        val ignoredReasons: String = "",
        val userInterference: Boolean = false,
        val outsideTouches: Int = 0,
        val boardKeptChanging: Boolean = false,
        val playExport: String = "",
        val matchLen: Int = 0,
        val extraMove: Boolean = false,
        val blueCleared: Int = 0,
        val totalCleared: Int = 0,
        val playUncertain: Boolean = false,
    )

    sealed class Decision {
        data class Hold(val reason: String) : Decision()
        data class Go(val permit: Permit) : Decision()
        data class Stop(val reason: String) : Decision()
    }

    sealed class HelpGesture {
        data class Tap(val x: Float, val y: Float, val note: String) : HelpGesture()
        data class Hold(val reason: String) : HelpGesture()
    }

    var phase: Phase = Phase.IDLE
        private set

    var verifiedCount: Int = 0
        private set

    var gesturesDispatched: Int = 0
        private set

    /** Swipes only. A booster tap does not count. */
    var swipesDispatched: Int = 0
        private set

    var swipesVerified: Int = 0
        private set

    /** Ranked moves to skip after one confirmed miss. Cleared when a swipe lands. */
    var playSkip: Int = 0
        private set

    /** The word is gone or the bar emptied. A sent tap is not itself a latch. */
    var boosterLatched: Boolean = false
        private set

    /** Three sent taps still showed ACTIVATE, or the tap never got a fresh frame. */
    var boosterGaveUp: Boolean = false
        private set

    var boosterAttempts: Int = 0
        private set

    private var boosterReadyToRetry: Boolean = false
    private var boosterBoardsSinceTap: Int = 0
    private var boosterHandlingStartedAt: Long = -1L
    private val boosterAttemptLog = ArrayList<String>()
    private var extraFollowUpUntilMs: Long = 0L
    private var lastBlockLogAtMs: Long = 0L
    private var previousPassLabel: Long? = null
    private var previousCapturedAtMs: Long = Long.MAX_VALUE

    /** Solo until three agreeing frames change it. */
    var screenMode: PlayMode = PlayMode.SOLO
        private set

    /** Latest classification, before the three-frame confirm. Turn guard uses this. */
    private var latestClassified: PlayMode = PlayMode.SOLO
    private var pendingMode: PlayMode? = null
    private var pendingModeFrames: Int = 0
    private var lastHudKind: String = PlayGate.OURS
    private var lastOpponentAtMs: Long = 0L
    private var zeroTimeReads: Int = 0

    var timeLeftSeconds: Int? = null
        private set

    var multiplier: Int? = null
        private set

    private var opponentWait: Boolean = false
    var helpsUsedThisTurn: Int = 0
        private set
    private var helpQuotaHeld: Boolean = false
    private var equippedBoosterId: String? = null
    var boosterTargetNote: String = "booster target=unverified equipped=unknown"
        private set
    var helpNote: String = "helps unread"
        private set
    var helpPhase: String = "idle"
        private set
    private var helpId: String? = null
    private var helpStartedAtMs: Long = 0L
    private var helpTargetX: Float = Float.NaN
    private var helpTargetY: Float = Float.NaN

    /** Move 1 may dispatch before a saved calibration exists. */
    var autoProbe: Boolean = false
        private set

    private var pendingAutoSave: Boolean = false

    var stopReason: String = ""
        private set

    var startedAtMs: Long = 0L
        private set

    var stoppedAtMs: Long = 0L
        private set

    private val moves = ArrayList<MoveRecord>()
    private var nextToken = 1L
    private var outstanding: Permit? = null
    private var openMove: OpenMove? = null

    /** Preconditions of the frame that started the session. Empty until then. */
    private var startExport: String = ""

    /** Touches outside the bubble during this session. Our own swipe is suppressed. */
    var outsideTouches: Int = 0
        private set

    private var suppressOutsideUntilMs: Long = 0L
    private var ownGestureOpen: Boolean = false
    private var ownPath: OwnPath? = null
    private var lastGestureAtMs: Long = 0L
    private var boosterWindowOpen: Boolean = false
    private var boosterWindowUntilMs: Long = 0L
    private var pauseUntilMs: Long = 0L
    private val outsideTimes = ArrayList<Long>()
    private val outsideLog = ArrayList<String>()
    private val idleLog = ArrayList<String>()
    private var lastIdleLogAtMs: Long = 0L

    /** Sequence of the frame that verified the previous move. 0 until then. */
    private var lastSettledFrameSequence: Long = 0L

    /** Reused calibration line, included in the export when set. */
    private var calibrationLine: String = ""

    /** Latest HUD classifier line, including hudState and scores. */
    private var hudTrace: String = ""

    private val gameLines = ArrayList<String>()
    private var pendingGameLog: GameMoveLog.Pending? = null

    private data class OpenMove(
        val number: Int,
        val startedAtMs: Long,
        val cells: String,
        val fromX: Float,
        val fromY: Float,
        val toX: Float,
        val toY: Float,
        val beforeHash: Long,
        val beforeUnknown: Int,
        val callback: String,
        val playExport: String = "",
        val matchLen: Int = 0,
        val extraMove: Boolean = false,
        val blueCleared: Int = 0,
        val totalCleared: Int = 0,
        val playUncertain: Boolean = false,
        val swipeSequence: Long = 0L,
        val beforeLabel: Long = beforeHash,
        val beforeCircles: Int? = null,
        val longSettle: Boolean = false,
        val frameAgeMs: Long = 0L,
        val labelsMatchedPrevious: Boolean = true,
        val onCellCenter: Boolean = true,
        /** False for an ACTIVATE tap. It settles, and it is not a verified swipe. */
        val countsAsSwipe: Boolean = true,
        var anchorLabel: Long? = null,
        var anchorKeys: LongArray? = null,
        var anchorMs: Long = 0L,
        var anchorSeq: Long = 0L,
        var playableSinceMs: Long = 0L,
        var ignoredCount: Int = 0,
        val ignoredReasons: ArrayList<String> = ArrayList(),
        var outsideTouches: Int = 0,
        var sawBoardChange: Boolean = false,
        var sawPass: Boolean = false,
    )

    private var unknownMoves: Int = 0
    private var menuStreak: Int = 0
    private var menuSinceMs: Long = 0L
    private var zeroCircleReads: Int = 0
    private var sawBrightCircles: Boolean = false
    private var circleBaseline: Int? = null
    private var circleReadingChanged: Boolean = false
    private var soloEvidence: Boolean = false
    private var opponentBarStreak: Int = 0
    private var lastHelpAttemptAtMs: Long = 0L
    private var lastCircleSequence: Long = -1L
    private var unchangedRetries: Int = 0
    private val decisionLog = ArrayList<String>()

    val isActive: Boolean
        get() = phase == Phase.RUNNING || phase == Phase.SETTLING

    fun label(): String = "10 LÉPÉS TESZT: $verifiedCount"

    /**
     * Zero bright circles stop only after a swipe has been sent, the row was
     * bright earlier in this session, and two later playable reads are both
     * zero. A zero at the start, before any swipe, is an unreadable row.
     */
    fun noteCircles(
        classifiable: Boolean,
        bright: Int?,
        playable: Boolean,
        nowMs: Long,
        frameSequence: Long = 0L,
    ): Decision? {
        if (phase != Phase.RUNNING && phase != Phase.SETTLING) return null
        if (!playable || !classifiable || bright == null) {
            if (!classifiable) zeroCircleReads = 0
            return null
        }
        if (boosterWindowActive(nowMs)) return null
        if (frameSequence == lastCircleSequence) return null
        lastCircleSequence = frameSequence
        if (circleBaseline == null) {
            circleBaseline = bright
        } else if (bright != circleBaseline) {
            circleReadingChanged = true
        }
        if (bright > 0) {
            sawBrightCircles = true
            zeroCircleReads = 0
            return null
        }
        if (!ContinuousPlay.stopOnZeroCircles(screenMode, timeLeftSeconds)) {
            zeroCircleReads = 0
            return null
        }
        // A zero before the first swipe, or a row that never changed, is unreadable.
        if (swipesDispatched < 1 || !circleReadingChanged || !sawBrightCircles) {
            zeroCircleReads = 0
            return null
        }
        zeroCircleReads += 1
        if (zeroCircleReads < 2) return null
        return decided(stop(nowMs, "STOP — moves spent"), "circles")
    }

    /**
     * Opponent's Turn stops immediately. A menu or popup is a hold until
     * [MENU_FRAMES] consecutive menu frames span [MENU_HOLD_MS]; any our-turn
     * frame clears that streak. An unknown HUD is logged and play continues.
     * A dimmed transition is ignored. Solo never increments the unknown streak.
     */
    /**
     * Stores the reading. [screenMode] changes only after [MODE_CONFIRM_FRAMES]
     * consecutive frames agree, so one odd banner does not flip the rules.
     */
    fun observeHud(hud: HudObservation) {
        val next = if (PlayFlags.modeAdapt) PlayMode.classify(hud) else PlayMode.BASIC
        timeLeftSeconds = hud.timeLeftSeconds
        multiplier = hud.multiplier
        latestClassified = next
        if (hud.soloPositive || hud.circlesClassifiable) {
            soloEvidence = true
        }
        opponentBarStreak = if (hud.turnState == HudObservation.TURN_OPPONENT) {
            opponentBarStreak + 1
        } else {
            0
        }
        if (next == screenMode) {
            pendingMode = null
            pendingModeFrames = 0
            return
        }
        if (pendingMode == next) {
            pendingModeFrames += 1
        } else {
            pendingMode = next
            pendingModeFrames = 1
        }
        if (pendingModeFrames >= MODE_CONFIRM_FRAMES) {
            screenMode = next
            pendingMode = null
            pendingModeFrames = 0
        }
    }

    /**
     * The equipped booster is not read from the HUD. A missing id does not
     * invent a second tap. A known target booster returns the simulator cell.
     */
    fun noteEquippedBooster(id: String?) {
        equippedBoosterId = id?.takeIf { it.isNotBlank() }
    }

    fun planBoosterTarget(registry: BoosterRegistry, board: Board?): TargetPicker.Target? {
        if (!PlayFlags.boosters || !PlayFlags.targetBoosters) {
            boosterTargetNote = "booster target=off"
            return null
        }
        val id = equippedBoosterId
        if (id == null) {
            boosterTargetNote = "booster target=unverified equipped=unknown"
            return null
        }
        val entry = registry.get(id)
        if (entry == null) {
            boosterTargetNote = "booster target=unverified equipped=$id missing"
            return null
        }
        if (!entry.needsTarget) {
            boosterTargetNote = "booster target=none equipped=$id activation=${entry.activation}"
            return null
        }
        if (board == null) {
            boosterTargetNote = "booster target=pending equipped=$id type=${entry.targetType}"
            return null
        }
        val target = TargetPicker().best(board, entry.targetType)
        boosterTargetNote =
            "booster target=${target.row},${target.col} type=${entry.targetType} " +
                "blue=${target.blue} total=${target.total} equipped=$id activation=${entry.activation}"
        return target
    }

    /**
     * One help step. A button tap is returned only when charges were read.
     * Hammer and Box then wait for "Pick a piece" before the cell tap.
     * Shuffle waits until the board is playable. A timeout resumes swipes.
     */
    fun considerHelp(
        hasLegalMove: Boolean,
        extraMoveAvailable: Boolean,
        hasThreeMatch: Boolean = hasLegalMove,
        boxReady: Boolean = false,
        charges: HelpPolicy.Charges = HelpPolicy.Charges(
            hammer = null,
            shuffle = null,
            geometryVerified = false,
        ),
    ): HelpPolicy.Choice? {
        val choice = HelpPolicy.choose(
            hasLegalMove = hasLegalMove,
            extraMoveAvailable = extraMoveAvailable,
            charges = charges,
            usedThisTurn = helpsUsedThisTurn,
            hasThreeMatch = hasThreeMatch,
            boxReady = boxReady,
        )
        if (helpPhase == "idle") {
            helpNote = when {
                !PlayFlags.helps -> "helps off"
                !charges.geometryVerified -> "helps unread geometry=unverified used=$helpsUsedThisTurn"
                choice != null -> "helps choice=${choice.id} reason=${choice.reason}"
                else -> "helps none used=$helpsUsedThisTurn hammer=${charges.hammer} shuffle=${charges.shuffle} box=${charges.box}"
            }
        }
        return choice
    }

    fun advanceHelp(
        nowMs: Long,
        promptVisible: Boolean,
        playable: Boolean,
        choice: HelpPolicy.Choice?,
        targetX: Float?,
        targetY: Float?,
    ): HelpGesture? {
        if (!PlayFlags.helps) return null
        if (helpPhase == "prompt") {
            if (nowMs - helpStartedAtMs > HELP_PROMPT_MS) {
                helpPhase = "idle"
                helpNote = "help prompt missed id=$helpId"
                notePlayBlock(nowMs, helpNote)
                return null
            }
            if (!promptVisible) {
                notePlayBlock(nowMs, "help waiting for pick a piece")
                return HelpGesture.Hold("help waiting for pick a piece")
            }
            if (targetX == null || targetY == null) {
                helpPhase = "idle"
                helpNote = "help target unverified id=$helpId"
                notePlayBlock(nowMs, helpNote)
                return null
            }
            helpPhase = "settle"
            helpStartedAtMs = nowMs
            helpNote = "help pick id=$helpId x=${targetX.toInt()} y=${targetY.toInt()}"
            notePlayBlock(nowMs, helpNote)
            return HelpGesture.Tap(targetX, targetY, helpNote)
        }
        if (helpPhase == "settle") {
            if (playable && nowMs - helpStartedAtMs >= 600L) {
                helpNote = "help settled id=$helpId"
                notePlayBlock(nowMs, helpNote)
                helpPhase = "idle"
                helpId = null
                return null
            }
            if (nowMs - helpStartedAtMs > HELP_SETTLE_MS) {
                helpPhase = "idle"
                helpNote = "help settle timeout id=$helpId"
                notePlayBlock(nowMs, helpNote)
                return null
            }
            notePlayBlock(nowMs, "help settling")
            return HelpGesture.Hold("help settling")
        }
        if (choice == null) return null
        if (lastHelpAttemptAtMs > 0L && nowMs - lastHelpAttemptAtMs < HELP_ATTEMPT_GAP_MS) {
            helpNote = "help cooldown"
            notePlayBlock(nowMs, helpNote)
            return null
        }
        lastHelpAttemptAtMs = nowMs
        val point = com.match3vision.analyzer.play.HelpButtons.center(choice.id) ?: return null
        helpId = choice.id
        helpStartedAtMs = nowMs
        helpTargetX = targetX ?: Float.NaN
        helpTargetY = targetY ?: Float.NaN
        helpPhase = if (choice.needsTarget) "prompt" else "settle"
        helpNote = "help tap=${choice.id} reason=${choice.reason}"
        notePlayBlock(nowMs, helpNote)
        return HelpGesture.Tap(point.x, point.y, helpNote)
    }

    /** The perk button tap completed. A failed or cancelled tap must not call this. */
    fun noteHelpSent() {
        if (helpQuotaHeld) return
        helpsUsedThisTurn += 1
        helpQuotaHeld = true
    }

    /** A sent help may hold the tick. A failed or unsent tap must not. */
    fun helpConsumesTurn(): Boolean = helpQuotaHeld && helpPhase != "idle"

    /** The tap was not sent. The quota stays where it was and the perk can be tried again. */
    fun abandonHelp(nowMs: Long, reason: String) {
        helpPhase = "idle"
        helpId = null
        helpStartedAtMs = 0L
        helpNote = reason
        notePlayBlock(nowMs, reason)
    }

    fun helpTarget(): Pair<Float, Float>? {
        if (helpTargetX.isNaN() || helpTargetY.isNaN()) return null
        return helpTargetX to helpTargetY
    }

    fun notePlayHud(kind: String, nowMs: Long): Decision? {
        if (phase != Phase.RUNNING && phase != Phase.SETTLING) return null
        lastHudKind = kind
        expireOpponentWait(nowMs, kind)
        if (screenMode == PlayMode.TIMER && timeLeftSeconds == 0) {
            zeroTimeReads += 1
        } else {
            zeroTimeReads = 0
        }
        if (zeroTimeReads >= 2 && ContinuousPlay.timeOut(screenMode, timeLeftSeconds)) {
            return decided(abort("STOP — time out", nowMs), "hud")
        }
        if (boosterWindowActive(nowMs) && (kind == PlayGate.MENU || kind == PlayGate.UNKNOWN)) {
            notePlayBlock(nowMs, "booster window")
            return decided(Decision.Hold("booster window"), "hud")
        }
        val decision: Decision? = when (kind) {
            PlayGate.OPPONENT -> when {
                soloEvidence && opponentBarStreak < OPPONENT_BAR_FRAMES -> {
                    notePlayBlock(nowMs, "solo opponent bar streak=$opponentBarStreak")
                    null
                }
                soloEvidence -> abort("STOP — Opponent's Turn", nowMs)
                else -> when (ContinuousPlay.opponentAction(latestClassified)) {
                ContinuousPlay.WAIT -> {
                    opponentWait = true
                    lastOpponentAtMs = nowMs
                    Decision.Hold("opponent turn")
                }
                ContinuousPlay.PLAY -> {
                    opponentWait = false
                    null
                }
                else -> abort("STOP — Opponent's Turn", nowMs)
                }
            }
            PlayGate.MENU -> {
                if (menuStreak == 0) menuSinceMs = nowMs
                menuStreak += 1
                if (menuStreak >= MENU_FRAMES && nowMs - menuSinceMs >= MENU_HOLD_MS) {
                    abort("STOP — menu or popup", nowMs)
                } else {
                    Decision.Hold("menu or popup")
                }
            }
            PlayGate.DIMMED -> Decision.Hold("dimmed transition")
            PlayGate.UNKNOWN -> null
            else -> {
                unknownMoves = 0
                if (kind == PlayGate.OURS) {
                    if (opponentWait) {
                        helpsUsedThisTurn = 0
                        helpQuotaHeld = false
                    }
                    opponentWait = false
                    menuStreak = 0
                    menuSinceMs = 0L
                }
                null
            }
        }
        if (decision != null) {
            if (decision is Decision.Hold) notePlayBlock(nowMs, decision.reason)
            return decided(decision, "hud")
        }
        logDecision("hud $kind")
        return null
    }

    /** Call when a swipe is actually sent, so the unknown streak counts moves. */
    fun noteDispatchedHud(kind: String, nowMs: Long = 0L) {
        if (boosterWindowActive(nowMs)) return
        if (kind == PlayGate.UNKNOWN) unknownMoves += 1
        else if (kind == PlayGate.OURS) unknownMoves = 0
    }

    fun movesSnapshot(): List<MoveRecord> = moves.toList()

    /** Earlier of the session limit and the open move's budget. */
    fun activeDeadlineMs(): Long {
        val sessionEnd = if (startedAtMs > 0L) startedAtMs + SESSION_LIMIT_MS else Long.MAX_VALUE
        val moveEnd = openMove?.let { it.startedAtMs + SETTLE_WAIT_MS } ?: sessionEnd
        return minOf(sessionEnd, moveEnd)
    }

    fun arm(nowMs: Long): Boolean {
        if (isActive) return false
        phase = Phase.RUNNING
        resetCounters()
        stopReason = ""
        startedAtMs = nowMs
        stoppedAtMs = 0L
        moves.clear()
        outstanding = null
        openMove = null
        nextToken = 1L
        startExport = ""
        outsideTouches = 0
        suppressOutsideUntilMs = 0L
        lastSettledFrameSequence = 0L
        calibrationLine = ""
        hudTrace = ""
        gameLines.clear()
        pendingGameLog = null
        resetBooster()
        autoProbe = false
        pendingAutoSave = false
        return true
    }

    /** Allow move 1 without a saved TESZT ÉRINTÉS hit. Later moves still need it. */
    fun enableAutoProbe() {
        if (phase == Phase.RUNNING && swipesDispatched == 0) autoProbe = true
    }

    fun needsGeometryCheck(): Boolean = autoProbe && swipesDispatched == 0

    fun openCells(): String? = openMove?.cells

    fun takeAutoSave(): Boolean = pendingAutoSave.also { pendingAutoSave = false }

    fun noteStartExport(text: String) {
        startExport = text.trim()
    }

    fun noteCalibration(text: String) {
        calibrationLine = text.trim()
    }

    /** Records the HUD reading for the export. Does not stop the session. */
    fun noteHud(text: String) {
        hudTrace = text.trim()
    }

    fun beginGameLog(pending: GameMoveLog.Pending) {
        pendingGameLog = pending
    }

    fun finishGameLog(
        after: GameMoveLog.BoardView?,
        verification: String,
        settleMs: Long,
        elapsedMs: Long,
        stopReason: String,
        userInterference: Boolean,
    ) {
        val pending = pendingGameLog ?: return
        pendingGameLog = null
        gameLines += GameMoveLog.finish(
            pending = pending,
            after = after,
            verification = verification,
            settleMs = settleMs,
            elapsedMs = elapsedMs,
            stopReason = stopReason,
            userInterference = userInterference,
        )
    }

    fun gameLogText(): String = buildString {
        gameLines.forEach { appendLine(it) }
        val pending = pendingGameLog
        if (pending != null) {
            appendLine(GameMoveLog.unfinished(pending, stopReason, pending.before.timestampMs))
        }
    }

    fun clear() {
        phase = Phase.IDLE
        resetCounters()
        stopReason = ""
        startedAtMs = 0L
        stoppedAtMs = 0L
        moves.clear()
        outstanding = null
        openMove = null
        startExport = ""
        outsideTouches = 0
        suppressOutsideUntilMs = 0L
        lastSettledFrameSequence = 0L
        calibrationLine = ""
        hudTrace = ""
        gameLines.clear()
        pendingGameLog = null
        resetBooster()
        autoProbe = false
        pendingAutoSave = false
    }

    private fun resetBooster() {
        boosterLatched = false
        boosterGaveUp = false
        boosterAttempts = 0
        boosterReadyToRetry = false
        boosterBoardsSinceTap = 0
        boosterHandlingStartedAt = -1L
        boosterAttemptLog.clear()
        previousPassLabel = null
        previousCapturedAtMs = Long.MAX_VALUE
    }

    /** Ignore ACTION_OUTSIDE that belongs to the swipe we just injected. */
    fun suppressOutsideTouchUntil(untilMs: Long) {
        if (untilMs > suppressOutsideUntilMs) suppressOutsideUntilMs = untilMs
    }

    /**
     * Our swipe or ACTIVATE tap is in flight. Outside events are ours until
     * [finishOwnGesture]. [x1]..[y2] is the tap point or the swipe segment.
     */
    fun beginOwnGesture(nowMs: Long = 0L) {
        ownGestureOpen = true
        if (nowMs > 0L) lastGestureAtMs = nowMs
    }

    fun beginOwnGesture(x1: Float, y1: Float, x2: Float, y2: Float, nowMs: Long = 0L) {
        if (x1.isFinite() && y1.isFinite() && x2.isFinite() && y2.isFinite()) {
            ownPath = OwnPath(x1, y1, x2, y2)
        }
        ownGestureOpen = true
        if (nowMs > 0L) lastGestureAtMs = nowMs
    }

    /** ACTIVATE was tapped. Outside touches and a lost HUD do not count until the board is playable again. */
    fun beginBoosterWindow(nowMs: Long) {
        boosterWindowOpen = true
        if (nowMs > 0L) lastGestureAtMs = nowMs
    }

    /** The booster's playable board arrived, or the tap did not land. */
    fun endBoosterWindow(nowMs: Long) {
        if (!boosterWindowOpen) return
        boosterWindowOpen = false
        boosterWindowUntilMs = nowMs + OWN_GESTURE_AFTER_MS
    }

    fun boosterWindowActive(nowMs: Long): Boolean {
        if (boosterWindowOpen) return true
        return boosterWindowUntilMs > 0L && nowMs > 0L && nowMs < boosterWindowUntilMs
    }

    /** Coordinates learned when the swipe gesture comes back from dispatch. */
    fun rememberOwnPath(x1: Float, y1: Float, x2: Float, y2: Float) {
        if (x1.isFinite() && y1.isFinite() && x2.isFinite() && y2.isFinite()) {
            ownPath = OwnPath(x1, y1, x2, y2)
        }
    }

    /** Callback returned. Keep ignoring outside events for [OWN_GESTURE_AFTER_MS]. */
    fun finishOwnGesture(nowMs: Long) {
        ownGestureOpen = false
        suppressOutsideTouchUntil(nowMs + OWN_GESTURE_AFTER_MS)
    }

    /**
     * A finger landed outside the bubble while this session was running.
     * Only a touchscreen finger [OverlayOutsideTouch] on our overlay counts.
     * An accessibility window or content event, or a board change, does not.
     */
    fun noteOutsideTouch(
        nowMs: Long,
        x: Float = Float.NaN,
        y: Float = Float.NaN,
        eventType: String = OverlayOutsideTouch.ACTION,
        sourceWindow: String = OverlayOutsideTouch.WINDOW,
        inputSource: String = OverlayOutsideTouch.TOUCHSCREEN,
        toolType: String = OverlayOutsideTouch.FINGER,
    ): Decision? {
        if (!OverlayOutsideTouch.counts(eventType, sourceWindow, inputSource, toolType)) {
            logOutside(nowMs, x, y, eventType, sourceWindow, "ignored-not-motion")
            return null
        }
        if (phase != Phase.RUNNING && phase != Phase.SETTLING) {
            logOutside(nowMs, x, y, eventType, sourceWindow, "ignored-inactive")
            return null
        }
        if (ownGestureOpen || nowMs < suppressOutsideUntilMs) {
            logOutside(nowMs, x, y, eventType, sourceWindow, "ignored-own-gesture")
            return null
        }
        if (boosterWindowActive(nowMs)) {
            logOutside(nowMs, x, y, eventType, sourceWindow, "ignored-booster-window")
            return null
        }
        if (nearOwnGesture(x, y)) {
            logOutside(nowMs, x, y, eventType, sourceWindow, "ignored-near-path")
            return null
        }
        if (idleSinceGesture(nowMs) > IDLE_WITHOUT_GESTURE_MS) {
            logOutside(nowMs, x, y, eventType, sourceWindow, "ignored-idle-help")
            return null
        }
        outsideTouches += 1
        openMove?.let { it.outsideTouches += 1 }
        outsideTimes.add(nowMs)
        while (outsideTimes.isNotEmpty() && nowMs - outsideTimes.first() > OUTSIDE_CLUSTER_MS) {
            outsideTimes.removeAt(0)
        }
        if (outsideTimes.size >= 2) {
            logOutside(nowMs, x, y, eventType, sourceWindow, "stop")
            return abort("STOP — user interference", nowMs)
        }
        pauseUntilMs = nowMs + OUTSIDE_PAUSE_MS
        logOutside(nowMs, x, y, eventType, sourceWindow, "pause")
        return Decision.Hold("pause — outside touch")
    }

    private fun logOutside(
        nowMs: Long,
        x: Float,
        y: Float,
        eventType: String,
        sourceWindow: String,
        result: String,
    ) {
        if (outsideLog.size >= 200) return
        val since = if (lastGestureAtMs > 0L) nowMs - lastGestureAtMs else -1L
        val xs = if (x.isFinite()) x.toString() else "none"
        val ys = if (y.isFinite()) y.toString() else "none"
        outsideLog += "tSinceGestureMs=$since x=$xs y=$ys event=$eventType source=$sourceWindow result=$result"
    }

    private fun nearOwnGesture(x: Float, y: Float): Boolean {
        val path = ownPath ?: return false
        if (!x.isFinite() || !y.isFinite()) return false
        return path.distanceTo(x, y) <= OWN_GESTURE_RADIUS_PX
    }

    private data class OwnPath(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
        fun distanceTo(x: Float, y: Float): Float {
            val dx = x2 - x1
            val dy = y2 - y1
            val len2 = dx * dx + dy * dy
            if (len2 <= 1f) return hypot(x - x1, y - y1)
            val t = (((x - x1) * dx + (y - y1) * dy) / len2).coerceIn(0f, 1f)
            return hypot(x - (x1 + t * dx), y - (y1 + t * dy))
        }
    }

    /**
     * Safety checks that must stop the session even between moves.
     * Returns null while the session may continue.
     */
    fun pollSafety(nowMs: Long, ownUi: Boolean, a11yConnected: Boolean): Decision.Stop? {
        if (!isActive) return null
        if (ownUi) return abort("STOP — our app is in the foreground", nowMs)
        if (!a11yConnected) return abort("STOP — accessibility lost", nowMs)
        if (nowMs - startedAtMs >= SESSION_LIMIT_MS) {
            return abort(sessionLimitText(), nowMs)
        }
        return null
    }

    fun requestDispatch(gates: Gates): Decision {
        if (phase == Phase.STOPPED) return Decision.Stop(stopReason.ifBlank { "stopped" })
        if (phase == Phase.IDLE) return Decision.Stop("10 LÉPÉS not armed")
        if (phase == Phase.SETTLING) {
            if (openMove == null) {
                phase = Phase.RUNNING
            } else if (!unstickSettle(gates)) {
                noteIdle(gates.nowMs, gates.visionPass, "settle in progress")
                return Decision.Hold("settle in progress")
            }
        }
        sessionLimit(gates.nowMs)?.let { return it }
        if (gates.nowMs < pauseUntilMs) return holdLogged(gates.nowMs, "pause — outside touch")
        immediateAbort(gates.nowMs, gates.ownUi, gates.a11yConnected)?.let { return it }
        expireOpponentWait(gates.nowMs, lastHudKind)
        if (opponentWait) {
            notePlayBlock(gates.nowMs, "opponent turn")
            return Decision.Hold("opponent turn")
        }
        if (turnUnconfirmed()) {
            notePlayBlock(gates.nowMs, "turn unconfirmed")
            return Decision.Hold("turn unconfirmed")
        }
        if (gesturesDispatched >= MAX_MOVES || verifiedCount >= MAX_MOVES) {
            return stop(gates.nowMs, safetyCapText())
        }
        if (outstanding != null) {
            if (gates.visionPass && idleSinceGesture(gates.nowMs) > IDLE_WITHOUT_GESTURE_MS) {
                noteIdle(gates.nowMs, true, "dispatch permit already issued")
                outstanding = null
            } else {
                notePlayBlock(gates.nowMs, "dispatch permit already issued")
                return Decision.Hold("dispatch permit already issued")
            }
        }
        val hold = holdReason(gates)
        if (hold != null) {
            notePlayBlock(gates.nowMs, hold)
            return Decision.Hold(hold)
        }
        val permit = Permit(token = nextToken++, moveNumber = gesturesDispatched + 1)
        outstanding = permit
        return Decision.Go(permit)
    }

    fun consumePermit(permit: Permit): Boolean {
        if (phase != Phase.RUNNING) return false
        val pending = outstanding ?: return false
        if (pending.token != permit.token || pending.moveNumber != permit.moveNumber) return false
        outstanding = null
        return true
    }

    /** The engine held without sending a gesture. Another frame may try. */
    fun releaseUnusedPermit() {
        if (phase != Phase.RUNNING) return
        outstanding = null
    }

    /** The ACTIVATE tap never got a fresh frame, or the 10 s budget ran out. Swipes continue. */
    fun giveUpBooster() {
        if (boosterGaveUp || boosterLatched) return
        boosterGaveUp = true
        boosterReadyToRetry = false
        val result = if (boosterAttempts == 0) "not-sent" else "budget"
        boosterAttemptLog += "attempt=$boosterAttempts result=$result"
    }

    /** A booster failure is logged. Play stays running. */
    fun noteBoosterException(message: String) {
        val clean = message.replace('\n', ' ').take(160)
        boosterAttemptLog += "attempt=$boosterAttempts result=exception $clean"
    }

    /**
     * One playable board. Returns immediately. Never waits for the word to
     * vanish and never holds a dispatch permit. After [BOOSTER_HANDLING_BUDGET_MS]
     * the booster is given up and the caller swipes.
     */
    @Suppress("UNUSED_PARAMETER")
    fun considerBoosterFrame(
        nowMs: Long,
        activateVisible: Boolean,
        barFull: Boolean,
        canSendNow: Boolean,
        needsTarget: Boolean = false,
        provenNoTarget: Boolean = false,
    ): BoosterStep {
        if (!PlayFlags.boosters) return BoosterStep.SWIPE
        val equipped = equippedBoosterId
        if (needsTarget && equipped != null) {
            boosterTargetNote = "booster target=blocked needsTarget equipped=$equipped"
            return BoosterStep.SWIPE
        }
        if (equipped == null) {
            boosterTargetNote = "id unknown"
        }
        if (phase != Phase.RUNNING) return BoosterStep.SWIPE
        val interested = !boosterLatched && !boosterGaveUp && (activateVisible || boosterAttempts > 0)
        if (interested && boosterHandlingStartedAt < 0L) {
            boosterHandlingStartedAt = nowMs
        }
        if (
            interested &&
            boosterHandlingStartedAt >= 0L &&
            nowMs - boosterHandlingStartedAt >= BOOSTER_HANDLING_BUDGET_MS
        ) {
            giveUpBooster()
        }
        if (!boosterLatched && !boosterGaveUp && boosterAttempts > 0) {
            noteBoosterBoard(activateVisible, barFull)
        }
        if (boosterMayTap() && canSendNow && activateVisible && phase == Phase.RUNNING) {
            return BoosterStep.TAP
        }
        return BoosterStep.SWIPE
    }

    /**
     * Another ACTIVATE tap is allowed before the first try, and again after
     * two playable boards still show the word. Three sent taps is the limit.
     */
    fun boosterMayTap(): Boolean =
        !boosterLatched && !boosterGaveUp && boosterAttempts < BOOSTER_MAX_ATTEMPTS &&
            (boosterAttempts == 0 || boosterReadyToRetry)

    /** One sent tap. The latch waits until the word is gone or the bar empties. */
    fun noteBoosterTap(x: Float, y: Float, durationMs: Long) {
        boosterAttempts += 1
        boosterReadyToRetry = false
        boosterBoardsSinceTap = 0
        boosterAttemptLog += "attempt=$boosterAttempts point=(${x.toInt()},${y.toInt()}) " +
            "durationMs=$durationMs result=sent"
    }

    /**
     * One playable board after a sent tap. Watching for two boards that still
     * show ACTIVATE requests a retry. The word disappearing rearms the next charge.
     * [barFull] is recorded with that result.
     */
    fun noteBoosterBoard(activateVisible: Boolean, barFull: Boolean): String {
        if ((boosterGaveUp || boosterLatched) && !activateVisible) {
            boosterAttemptLog += "result=rearmed"
            rearmBooster()
            return "rearmed"
        }
        if (boosterLatched || boosterGaveUp || boosterAttempts == 0) return "idle"
        if (!activateVisible) {
            boosterAttemptLog += "attempt=$boosterAttempts result=registered barFull=$barFull"
            rearmBooster()
            return "rearmed"
        }
        boosterBoardsSinceTap += 1
        if (boosterBoardsSinceTap < BOOSTER_CONFIRM_BOARDS) {
            boosterAttemptLog += "attempt=$boosterAttempts result=still-visible board=$boosterBoardsSinceTap"
            return "watching"
        }
        if (boosterAttempts >= BOOSTER_MAX_ATTEMPTS) {
            boosterGaveUp = true
            boosterReadyToRetry = false
            boosterAttemptLog += "attempt=$boosterAttempts result=missed"
            return "missed"
        }
        boosterBoardsSinceTap = 0
        boosterReadyToRetry = true
        boosterAttemptLog += "attempt=$boosterAttempts result=retry"
        return "retry"
    }

    /** The word is gone, so the next time ACTIVATE appears it may be tapped again. */
    private fun rearmBooster() {
        boosterLatched = false
        boosterGaveUp = false
        boosterAttempts = 0
        boosterReadyToRetry = false
        boosterBoardsSinceTap = 0
        boosterHandlingStartedAt = -1L
    }

    fun boosterLog(): String = boosterAttemptLog.joinToString("\n")

    /**
     * Null when this frame may be swiped. A rejection is logged with [ageMs]
     * and [rankMs]. Allowing a swipe does not consume the extra-move window
     * and does not skip the newest-frame cell check. The window clears in
     * [noteGesture], when a swipe is actually sent.
     */
    fun considerSwipeFrame(
        ageMs: Long,
        labelHash: Long,
        nowMs: Long = 0L,
        capturedAtMs: Long = Long.MIN_VALUE,
        rankMs: Long = -1L,
    ): String? {
        val previous = previousPassLabel
        val previousCaptured = previousCapturedAtMs
        val captured = when {
            capturedAtMs != Long.MIN_VALUE -> capturedAtMs
            nowMs > 0L -> (nowMs - ageMs).coerceAtLeast(0L)
            else -> Long.MAX_VALUE
        }
        previousPassLabel = labelHash
        previousCapturedAtMs = captured
        val block = SwipeGuard.motionBlock(
            ageMs,
            previous,
            labelHash,
            captured,
            previousCaptured,
            lastGestureAtMs,
        )
        swipeOverride = false
        if (block == null) return null
        if (nowMs > 0L) notePlayBlock(nowMs, block, ageMs, rankMs)
        return block
    }

    /**
     * Kept so a caller can see that a stability pass did not skip the cell
     * re-check. It stays false: the newest frame is always re-checked.
     */
    var swipeOverride: Boolean = false
        private set

    /** Exported under `--- IDLE ---`. The last [IDLE_RING] lines are kept. */
    fun notePlayBlock(nowMs: Long, reason: String, ageMs: Long = -1L, rankMs: Long = -1L) {
        lastBlockLogAtMs = nowMs
        val extra = buildString {
            if (ageMs >= 0L) append(" ageMs=$ageMs")
            if (rankMs >= 0L) append(" rankMs=$rankMs")
        }
        pushIdle("idle reason=$reason$extra")
    }

    /**
     * Records one ACTIVATE tap. A miss latches the booster and leaves the
     * session running. A stable board change counts as a verified move.
     */
    fun recordBooster(
        changed: Boolean,
        stable: Boolean,
        callbackCompleted: Boolean,
        x: Float,
        y: Float,
        nowMs: Long,
        playExport: String,
        countChange: Boolean = true,
        needsTarget: Boolean = false,
    ): String {
        noteBoosterTap(x, y, BOOSTER_TAP_MS)
        if (phase != Phase.RUNNING) return "not running"
        if (gesturesDispatched >= MAX_MOVES) return "cap"
        gesturesDispatched += 1
        val verification = when {
            !countChange -> "FAILED — board changed during the opponent's turn"
            needsTarget -> "HOLD — booster needs a target; no target tap"
            !callbackCompleted -> "FAILED — booster tap callback was not completed"
            changed && stable -> "PASS — booster ACTIVATE, board changed, stable"
            changed -> "FAILED — booster board changed but did not settle"
            else -> "FAILED — booster tap did not change the board"
        }
        record(
            number = gesturesDispatched,
            cells = "booster ACTIVATE",
            fromX = x,
            fromY = y,
            toX = x,
            toY = y,
            callback = if (callbackCompleted) "onCompleted" else "cancelled",
            verification = verification,
            beforeUnknown = 0,
            afterUnknown = 0,
            startedAtMs = nowMs,
            finishedAtMs = nowMs,
            playExport = playExport,
        )
        if (countChange && changed && stable && callbackCompleted) {
            verifiedCount += 1
            if (verifiedCount >= MAX_MOVES) {
                stop(nowMs, safetyCapText())
            }
        }
        return verification
    }

    /**
     * An ACTIVATE tap settles on the same playable-board gate as a swipe.
     * The poll that noticed the tap does not make the next frame swipable.
     */
    fun armBoosterSettle(
        x: Float,
        y: Float,
        nowMs: Long,
        playExport: String,
        swipeSequence: Long,
        beforeHash: Long,
        beforeLabel: Long,
        beforeCircles: Int?,
        countTap: Boolean = true,
        callbackCompleted: Boolean = true,
    ): Decision {
        if (phase != Phase.RUNNING) return Decision.Stop(stopReason.ifBlank { "not running" })
        if (countTap) noteBoosterTap(x, y, BOOSTER_TAP_MS)
        sessionLimit(nowMs)?.let { return it }
        if (gesturesDispatched >= MAX_MOVES) {
            return stop(nowMs, safetyCapText())
        }
        gesturesDispatched += 1
        boosterWindowOpen = true
        if (nowMs > 0L) lastGestureAtMs = nowMs
        openMove = OpenMove(
            number = gesturesDispatched,
            startedAtMs = nowMs,
            cells = "booster ACTIVATE",
            fromX = x,
            fromY = y,
            toX = x,
            toY = y,
            beforeHash = beforeHash,
            beforeUnknown = 0,
            callback = if (callbackCompleted) "onCompleted" else "unconfirmed",
            playExport = playExport,
            swipeSequence = swipeSequence,
            beforeLabel = beforeLabel,
            beforeCircles = beforeCircles,
            longSettle = true,
            countsAsSwipe = false,
        )
        phase = Phase.SETTLING
        return Decision.Hold("settling booster")
    }

    fun noteGesture(fact: GestureFact): Decision {
        if (phase != Phase.RUNNING) return Decision.Stop(stopReason.ifBlank { "not running" })
        sessionLimit(fact.nowMs)?.let { return it }
        if (gesturesDispatched >= MAX_MOVES) {
            return stop(fact.nowMs, safetyCapText())
        }
        gesturesDispatched += 1
        swipesDispatched += 1
        extraFollowUpUntilMs = 0L
        if (fact.startedAtMs > 0L) lastGestureAtMs = fact.startedAtMs
        val callback = when {
            fact.cancelled || !fact.callbackCompleted -> "cancelled"
            else -> "onCompleted"
        }
        if (fact.cancelled || !fact.callbackCompleted) {
            record(
                number = gesturesDispatched,
                cells = fact.cells,
                fromX = fact.fromX,
                fromY = fact.fromY,
                toX = fact.toX,
                toY = fact.toY,
                callback = callback,
                verification = "FAILED — gesture callback was not completed",
                beforeUnknown = fact.beforeUnknown,
                afterUnknown = fact.beforeUnknown,
                startedAtMs = fact.startedAtMs,
                finishedAtMs = fact.nowMs,
                playExport = fact.playExport,
                matchLen = fact.matchLen,
                extraMove = fact.extraMove,
                blueCleared = fact.blueCleared,
                totalCleared = fact.totalCleared,
                playUncertain = fact.playUncertain,
            )
            return Decision.Hold("gesture callback was not completed")
        }
        openMove = OpenMove(
            number = gesturesDispatched,
            startedAtMs = fact.startedAtMs,
            cells = fact.cells,
            fromX = fact.fromX,
            fromY = fact.fromY,
            toX = fact.toX,
            toY = fact.toY,
            beforeHash = fact.beforeHash,
            beforeUnknown = fact.beforeUnknown,
                callback = callback,
                playExport = fact.playExport,
                matchLen = fact.matchLen,
                extraMove = fact.extraMove,
                blueCleared = fact.blueCleared,
                totalCleared = fact.totalCleared,
                playUncertain = fact.playUncertain,
                swipeSequence = fact.swipeSequence,
                beforeLabel = fact.beforeLabel,
                beforeCircles = fact.beforeCircles,
                longSettle = fact.longSettle,
                frameAgeMs = fact.frameAgeMs,
                labelsMatchedPrevious = fact.labelsMatchedPrevious,
                onCellCenter = fact.onCellCenter,
            )
        phase = Phase.SETTLING
        return Decision.Hold("settling move $gesturesDispatched")
    }

    fun onSettle(sample: SettleSample): Decision {
        val open = openMove
        if (phase != Phase.SETTLING || open == null) {
            return decided(Decision.Stop(stopReason.ifBlank { "not settling" }), "settle")
        }
        immediateAbort(sample.nowMs, sample.ownUi, sample.a11yConnected)?.let { decision ->
            closeOpen(open, sample, "FAILED — ${decision.reason}")
            return decided(decision, "settle")
        }
        if (sample.nowMs < pauseUntilMs) return settleHold(open, sample, "pause — outside touch")
        if (sample.nowMs - startedAtMs >= SESSION_LIMIT_MS) {
            closeOpen(open, sample, "FAILED — ${SESSION_LIMIT_MS / 1_000}s session limit")
            return decided(stop(sample.nowMs, sessionLimitText()), "settle")
        }
        if (!sample.countBoardChange) {
            if (!soloEvidence) {
                opponentBarStreak = OPPONENT_BAR_FRAMES
            } else if (opponentBarStreak < OPPONENT_BAR_FRAMES) {
                opponentBarStreak += 1
            }
            if (!soloEvidence || opponentBarStreak >= OPPONENT_BAR_FRAMES) {
                val changed = sample.boardHash != open.beforeHash
                val verification = if (changed) {
                    "FAILED — board changed during the opponent's turn"
                } else {
                    "FAILED — opponent turn before the move was verified"
                }
                closeOpen(open, sample, verification)
                return decided(stop(sample.nowMs, "STOP — opponent turn during verification"), "settle")
            }
        } else {
            opponentBarStreak = 0
        }
        if (sample.diffFraction != null && sample.diffFraction > STABLE_FRACTION) {
            open.sawBoardChange = true
        }
        if (sample.visionPass && sample.unknownCount <= PLAY_UNKNOWN_LIMIT) open.sawPass = true
        if (open.countsAsSwipe &&
            sample.nowMs - open.startedAtMs >= SETTLE_WAIT_MS &&
            !open.sawPass
        ) {
            closeOpen(open, sample, "FAILED — settle wait ${SETTLE_WAIT_MS}ms, no PASS board")
            return decided(stop(sample.nowMs, "STOP — settle wait, no PASS board"), "settle")
        }
        if (sample.dimmed) return settleHold(open, sample, "dimmed transition")
        val ageLimit = maxOf(SETTLE_FRAME_AGE_MS, sample.cadenceMedianMs * 3L)
        val age = when {
            sample.frameAgeMs > 0L -> sample.frameAgeMs
            !sample.frameFresh -> ageLimit + 1L
            else -> 0L
        }
        if (age > ageLimit) return settleHold(open, sample, "frame not fresh")
        if (!sample.overlayOutside) return settleHold(open, sample, "overlay on the board")
        if (!sample.roiPlausible) return settleHold(open, sample, "implausible ROI")
        if (!sample.visionPass || sample.unknownCount > PLAY_UNKNOWN_LIMIT) {
            return settleHold(open, sample, "vision HOLD unk=${sample.unknownCount}")
        }
        if (!sample.capturedAfterGesture || sample.frameSequence <= open.swipeSequence) {
            return settleHold(open, sample, "frame is from before the gesture")
        }
        val captured = sample.nowMs - age.coerceAtLeast(0L)
        val labels = sample.labelHash ?: sample.boardHash
        val labelsDiffer = labels != open.beforeLabel
        val brightNow = sample.circlesBright
        val beforeCircles = open.beforeCircles
        val drop = beforeCircles != null &&
            sample.circlesClassifiable &&
            brightNow != null &&
            brightNow == beforeCircles - 1
        val gap = PLAYABLE_GAP_MS
        val keys = sample.labelKeys
        val anchored = open.anchorLabel != null && when {
            keys != null && open.anchorKeys != null -> Board.labelsWithinOne(open.anchorKeys!!, keys)
            else -> open.anchorLabel == labels
        }
        if (!anchored) {
            open.anchorLabel = labels
            open.anchorKeys = keys?.copyOf()
            open.anchorMs = captured
            open.anchorSeq = sample.frameSequence
            return settleHold(open, sample, "first stable label pixelDiff=${sample.diffFraction ?: "none"}")
        }
        if (sample.frameSequence <= open.anchorSeq || captured - open.anchorMs < gap) {
            return settleHold(open, sample, "label pair too close")
        }
        val spent = noteCircles(
            classifiable = sample.circlesClassifiable,
            bright = sample.circlesBright,
            playable = true,
            nowMs = sample.nowMs,
            frameSequence = sample.frameSequence,
        )
        if (!open.countsAsSwipe) {
            previousPassLabel = labels
            previousCapturedAtMs = (sample.nowMs - sample.frameAgeMs).coerceAtLeast(0L)
            return acceptBooster(open, sample, spent)
        }
        val circlesSame = open.beforeCircles != null &&
            sample.circlesClassifiable &&
            sample.circlesBright == open.beforeCircles
        if (labelsDiffer || drop) {
            if (autoProbe && swipesVerified == 0 && !sample.swapOverlaps) {
                closeOpen(open, sample, "FAILED — auto-calibration missed the swapped cells")
                openMove = null
                phase = Phase.RUNNING
                return decided(Decision.Hold("auto-calibration missed, continue"), "settle")
            }
            if (sample.frameSequence > lastSettledFrameSequence) {
                lastSettledFrameSequence = sample.frameSequence
            }
            val extraNote = if (circlesSame && labelsDiffer) " extra-move circles unchanged" else ""
            if (circlesSame && labelsDiffer) {
                extraFollowUpUntilMs = sample.nowMs + EXTRA_FOLLOW_UP_MS
            }
            previousPassLabel = labels
            previousCapturedAtMs = (sample.nowMs - sample.frameAgeMs).coerceAtLeast(0L)
            closeOpen(
                open,
                sample,
                "PASS — callback completed, board changed, labels stable, fresh, ROI plausible, vision PASS$extraNote",
            )
            if (autoProbe && swipesVerified == 0) {
                autoProbe = false
                pendingAutoSave = true
                if (calibrationLine.isBlank()) calibrationLine = AutoCalibration.NOTE
            }
            verifiedCount += 1
            if (open.countsAsSwipe) swipesVerified += 1
            playSkip = 0
            unchangedRetries = 0
            openMove = null
            if (spent != null) return decided(spent, "settle")
            if (verifiedCount >= MAX_MOVES) {
                return decided(stop(sample.nowMs, safetyCapText()), "settle")
            }
            phase = Phase.RUNNING
            return decided(Decision.Hold("move $verifiedCount verified$extraNote"), "settle")
        }
        if (open.playableSinceMs == 0L) open.playableSinceMs = sample.nowMs
        val landed = scaled(LANDED_WINDOW_MS, sample.cadenceMedianMs)
        if (sample.nowMs - open.playableSinceMs < landed) {
            return settleHold(open, sample, "confirming the board did not change")
        }
        unchangedRetries += 1
        playSkip = 1
        val why = SwipeGuard.unverifiedReason(
            open.frameAgeMs,
            open.labelsMatchedPrevious,
            open.onCellCenter,
        )
        closeOpen(open, sample, "RETRY — board unchanged, $why")
        openMove = null
        phase = Phase.RUNNING
        return decided(Decision.Hold("retry next move"), "settle")
    }

    fun abort(reason: String, nowMs: Long): Decision.Stop {
        if (phase == Phase.IDLE) return Decision.Stop(reason)
        if (phase == Phase.STOPPED) return Decision.Stop(stopReason.ifBlank { reason })
        val open = openMove
        if (open != null) {
            record(
                number = open.number,
                cells = open.cells,
                fromX = open.fromX,
                fromY = open.fromY,
                toX = open.toX,
                toY = open.toY,
                callback = open.callback,
                verification = "FAILED — $reason",
                beforeUnknown = open.beforeUnknown,
                afterUnknown = -1,
                startedAtMs = open.startedAtMs,
                finishedAtMs = nowMs,
                ignoredTransient = open.ignoredCount,
                ignoredReasons = open.ignoredReasons.joinToString("; "),
                userInterference = open.outsideTouches > 0 || reason.contains("user interference"),
                outsideTouches = open.outsideTouches,
                boardKeptChanging = open.sawBoardChange,
                playExport = open.playExport,
                matchLen = open.matchLen,
                extraMove = open.extraMove,
                blueCleared = open.blueCleared,
                totalCleared = open.totalCleared,
                playUncertain = open.playUncertain,
            )
            openMove = null
        }
        return stop(nowMs, reason)
    }

    fun report(): String = buildString {
        if (startExport.isNotBlank()) {
            appendLine(startExport.trimEnd())
        }
        appendLine("--- 10 LÉPÉS TESZT ---")
        appendLine("sessionLimitMs=$SESSION_LIMIT_MS")
        appendLine("settleWaitMs=$SETTLE_WAIT_MS")
        appendLine("postSwipeMs=$POST_SWIPE_MS")
        appendLine("postSwipeBigMs=$POST_SWIPE_BIG_MS")
        appendLine("playableGapMs=$PLAYABLE_GAP_MS")
        appendLine("settleFrameAgeMs=$SETTLE_FRAME_AGE_MS")
        appendLine("pollStepMs=$POLL_STEP_MS")
        appendLine(
            "expected: play while bright move circles remain and the turn is ours; " +
                "safety cap $MAX_MOVES gestures and ${SESSION_LIMIT_MS}ms; " +
                "a move waits up to ${SETTLE_WAIT_MS}ms for two matching label frames",
        )
        appendLine(
            "settle: consecutive coarse ROI samples whose changed fraction is " +
                "<= $STABLE_FRACTION. This path does not use a fixed animation sleep.",
        )
        val end = when {
            phase == Phase.IDLE -> startedAtMs
            stoppedAtMs > 0L -> stoppedAtMs
            moves.isNotEmpty() -> moves.last().finishedAtMs
            else -> startedAtMs
        }
        val measured = if (phase == Phase.IDLE) 0L else (end - startedAtMs).coerceAtLeast(0L)
        appendLine("measuredSessionMs=$measured")
        appendLine(
            "verified=$verifiedCount gestures=$gesturesDispatched " +
                "swipes=$swipesDispatched swipesVerified=$swipesVerified safetyCap=$MAX_MOVES",
        )
        appendLine(PlayMoveRanker.EXTRA_MOVE_RULE)
        if (calibrationLine.isNotBlank()) appendLine(calibrationLine)
        appendLine("predictedExtraMoveMatches=${moves.count { it.extraMove }}")
        appendLine("predictedBlueCleared=${moves.sumOf { it.blueCleared }}")
        appendLine("uncertainDecisions=${moves.count { it.playUncertain }}")
        appendLine(
            "changedUnsettled is not a verified move. " +
                "A move counts only after a fresh stable PASS board differs from the pre-move board.",
        )
        appendLine("stop=${stopReason.ifBlank { "none" }}")
        appendLine(
            "playMode=$screenMode timeLeft=${timeLeftSeconds?.toString() ?: "none"} " +
                "multiplier=${multiplier?.toString() ?: "none"}",
        )
        appendLine(PlayFlags.log())
        appendLine(boosterTargetNote)
        appendLine(helpNote)
        appendLine("--- BOOSTER ---")
        if (boosterAttemptLog.isEmpty()) appendLine("none")
        boosterAttemptLog.forEach { appendLine(it) }
        appendLine(hudTrace.ifBlank { "hudState=UNKNOWN" })
        appendLine("outsideTouches=$outsideTouches")
        appendLine("--- OUTSIDE ---")
        if (outsideLog.isEmpty()) appendLine("none")
        outsideLog.forEach { appendLine(it) }
        appendLine(
            "board-change verification cannot tell our gesture from a finger on the glass. " +
                "A detected touch outside the bubble is user interference and that move is not counted. " +
                "No detected touch does not prove the glass was untouched.",
        )
        if (moves.isEmpty()) {
            appendLine("moves: none")
        }
        moves.forEach { move ->
            appendLine(
                "move=${move.number} cells=${move.cells} " +
                    "from=(${move.fromX},${move.fromY}) to=(${move.toX},${move.toY}) " +
                    "callback=${move.callback} verify=${move.verification} " +
                    "unkBefore=${move.beforeUnknown} unkAfter=${move.afterUnknown} " +
                    "t0=${move.startedAtMs} t1=${move.finishedAtMs} durationMs=${move.durationMs}",
            )
            appendLine("frameBefore=five-move/move-%02d-before.png".format(move.number))
            appendLine("frameAfter=five-move/move-%02d-after.png".format(move.number))
            appendLine(
                "ignoredTransient=${move.ignoredTransient} " +
                    "ignoredReasons=${move.ignoredReasons.ifBlank { "none" }} " +
                    "userInterference=${move.userInterference} outsideTouches=${move.outsideTouches} " +
                    "boardKeptChanging=${move.boardKeptChanging} settleMs=${move.durationMs} " +
                    "settleWaitMs=$SETTLE_WAIT_MS",
            )
            if (move.playExport.isNotBlank()) {
                appendLine(
                    "play matchLen=${move.matchLen} extraMove=${if (move.extraMove) "yes" else "no"} " +
                        "blueCleared=${move.blueCleared} totalCleared=${move.totalCleared} " +
                        "uncertain=${if (move.playUncertain) "yes" else "no"}",
                )
                appendLine(move.playExport.trimEnd())
            }
        }
        appendLine("--- IDLE ---")
        if (idleLog.isEmpty()) appendLine("none")
        idleLog.forEach { appendLine(it) }
        appendLine("--- DECISIONS ---")
        if (decisionLog.isEmpty()) appendLine("none")
        decisionLog.forEach { appendLine(it) }
        appendLine("--- GAME LOG ---")
        appendLine("format=jsonl")
        appendLine("personalData=none")
        val log = gameLogText()
        if (log.isBlank()) appendLine("moves: none") else append(log)
    }

    private fun holdReason(gates: Gates): String? = when {
        !gates.selfCheckMeasured && !autoProbeOpen() ->
            "HOLD — self-check is not MEASURED_WITHIN_TOLERANCE"
        !gates.overlayCollapsed -> "HOLD — overlay is not collapsed"
        !gates.overlayOutsideRoi -> "HOLD — overlay intersects ROI"
        gates.msSinceCollapse < MIN_POST_COLLAPSE_MS ->
            "HOLD — waiting ${MIN_POST_COLLAPSE_MS}ms after collapse"
        !gates.frameFresh -> "HOLD — frame is not fresh"
        !gates.visionPass -> "HOLD — vision gates are not PASS"
        !gates.roiPlausible -> "HOLD — ROI implausible — waiting for a PASS frame"
        gates.frameSequence > 0L && gates.frameSequence <= lastSettledFrameSequence ->
            "HOLD — frame was captured before the previous move settled"
        else -> null
    }

    private fun sessionLimit(nowMs: Long): Decision.Stop? {
        if (phase != Phase.RUNNING && phase != Phase.SETTLING) return null
        if (nowMs - startedAtMs >= SESSION_LIMIT_MS) {
            return stop(nowMs, sessionLimitText())
        }
        return null
    }

    private fun sessionLimitText(): String = "STOP — ${SESSION_LIMIT_MS / 1_000}s session limit"

    private fun safetyCapText(): String = "STOP — $MAX_MOVES gesture safety cap"

    private fun immediateAbort(
        nowMs: Long,
        ownUi: Boolean,
        a11yConnected: Boolean,
    ): Decision.Stop? = when {
        ownUi -> stop(nowMs, "STOP — our app is in the foreground")
        !a11yConnected -> stop(nowMs, "STOP — accessibility lost")
        else -> null
    }

    private fun autoProbeOpen(): Boolean = autoProbe && swipesVerified == 0

    private fun qualifiesAsPass(sample: SettleSample): Boolean =
        sample.capturedAfterGesture &&
            sample.frameFresh &&
            sample.overlayOutside &&
            sample.roiPlausible &&
            sample.visionPass &&
            sample.unknownCount <= PLAY_UNKNOWN_LIMIT

    private fun transientReason(sample: SettleSample): String = when {
        !sample.capturedAfterGesture -> "frame is from before the gesture"
        !sample.frameFresh -> "frame not fresh"
        !sample.overlayOutside -> "overlay on the board"
        !sample.roiPlausible -> "implausible ROI"
        !sample.visionPass -> "vision HOLD unk=${sample.unknownCount}"
        sample.unknownCount > PLAY_UNKNOWN_LIMIT -> "unk=${sample.unknownCount}"
        else -> "not a PASS frame"
    }

    /**
     * A booster past [BOOSTER_PLAYABLE_MS] must not keep the session in settle.
     * A vision PASS board that has been waiting past [IDLE_WITHOUT_GESTURE_MS]
     * records why, once per [IDLE_LOG_EVERY_MS].
     */
    private fun settleHold(open: OpenMove, sample: SettleSample, reason: String): Decision {
        if (sample.visionPass) noteIdle(sample.nowMs, true, reason)
        if (!open.countsAsSwipe && sample.nowMs - open.startedAtMs >= BOOSTER_PLAYABLE_MS) {
            return finishBoosterWithoutBoard(open, sample)
        }
        if (open.countsAsSwipe &&
            open.sawPass &&
            sample.nowMs - open.startedAtMs >= SETTLE_WAIT_MS
        ) {
            return releasePassSettle(open, sample)
        }
        return ignore(open, reason)
    }

    /** Two agreeing PASS frames after ACTIVATE. A label or circle change is not required. */
    private fun acceptBooster(open: OpenMove, sample: SettleSample, spent: Decision?): Decision {
        if (sample.frameSequence > lastSettledFrameSequence) {
            lastSettledFrameSequence = sample.frameSequence
        }
        closeOpen(open, sample, "PASS — booster playable, labels stable, specials ignored")
        verifiedCount += 1
        playSkip = 0
        unchangedRetries = 0
        if (spent != null) return decided(spent, "settle")
        if (verifiedCount >= MAX_MOVES) {
            return decided(stop(sample.nowMs, safetyCapText()), "settle")
        }
        phase = Phase.RUNNING
        return decided(Decision.Hold("booster playable"), "settle")
    }

    /** PASS frames that never formed a pair are not a stop. The next swipe may go. */
    private fun releasePassSettle(open: OpenMove, sample: SettleSample): Decision {
        closeOpen(open, sample, "LOG — pass boards did not settle")
        phase = Phase.RUNNING
        return decided(Decision.Hold("continue — pass boards still changing"), "settle")
    }

    private fun finishBoosterWithoutBoard(open: OpenMove, sample: SettleSample): Decision {
        val reason = "no playable board within ${BOOSTER_PLAYABLE_MS}ms"
        closeOpen(open, sample, "LOG — $reason")
        phase = Phase.RUNNING
        return decided(Decision.Hold(reason), "settle")
    }

    /**
     * Settle that already left its loop still answers [requestDispatch] with
     * "settle in progress" and never looks at another frame. A booster past
     * its playable window, or any settle that has seen PASS boards for more
     * than [IDLE_WITHOUT_GESTURE_MS] since the last gesture, goes back to play.
     */
    private fun unstickSettle(gates: Gates): Boolean {
        val open = openMove ?: return false
        val boosterDue = !open.countsAsSwipe &&
            gates.nowMs - open.startedAtMs >= BOOSTER_PLAYABLE_MS
        val idleDue = gates.visionPass && idleSinceGesture(gates.nowMs) > IDLE_WITHOUT_GESTURE_MS
        if (!boosterDue && !idleDue) return false
        val reason = if (boosterDue) {
            "no playable board within ${BOOSTER_PLAYABLE_MS}ms"
        } else {
            "settle in progress"
        }
        noteIdle(gates.nowMs, gates.visionPass, reason)
        closeOpen(
            open,
            SettleSample(
                nowMs = gates.nowMs,
                boardHash = open.beforeHash,
                diffFraction = null,
                frameFresh = gates.frameFresh,
                roiPlausible = gates.roiPlausible,
                visionPass = gates.visionPass,
                unknownCount = -1,
                ownUi = gates.ownUi,
                a11yConnected = gates.a11yConnected,
            ),
            if (boosterDue) "LOG — $reason" else "LOG — idle, $reason",
        )
        if (phase == Phase.SETTLING) phase = Phase.RUNNING
        return true
    }

    private fun idleSinceGesture(nowMs: Long): Long {
        val origin = if (lastGestureAtMs > 0L) lastGestureAtMs else startedAtMs
        if (nowMs <= origin) return 0L
        return nowMs - origin
    }

    private fun noteIdle(nowMs: Long, visionPass: Boolean, reason: String) {
        val logged = if (visionPass) reason else "$reason visionHold"
        notePlayBlock(nowMs, logged)
    }

    private fun holdLogged(nowMs: Long, reason: String): Decision.Hold {
        notePlayBlock(nowMs, reason)
        return Decision.Hold(reason)
    }

    private fun pushIdle(line: String) {
        idleLog.add(line)
        while (idleLog.size > IDLE_RING) idleLog.removeAt(0)
    }

    /** PvP and an unrecognized layout do not dispatch until the turn is ours. */
    private fun turnUnconfirmed(): Boolean {
        if (latestClassified != PlayMode.PVP && latestClassified != PlayMode.BASIC) return false
        if (latestClassified == PlayMode.PVP && lastHudKind == PlayGate.OURS) return false
        return true
    }

    private fun expireOpponentWait(nowMs: Long, kind: String) {
        if (!opponentWait || kind == PlayGate.OPPONENT || lastOpponentAtMs <= 0L) return
        if (nowMs - lastOpponentAtMs < IDLE_WITHOUT_GESTURE_MS) return
        opponentWait = false
        notePlayBlock(nowMs, "opponent wait expired")
    }

    private fun ignore(open: OpenMove, reason: String): Decision.Hold {
        open.ignoredCount += 1
        if (open.ignoredReasons.size < MAX_IGNORED_LINES) open.ignoredReasons.add(reason)
        return decided(Decision.Hold("settling — $reason"), "settle") as Decision.Hold
    }

    private fun logDecision(text: String) {
        if (decisionLog.size < 400) decisionLog.add(text)
    }

    private fun decided(result: Decision, where: String): Decision {
        val reason = when (result) {
            is Decision.Hold -> result.reason
            is Decision.Stop -> result.reason
            is Decision.Go -> "go ${result.permit.moveNumber}"
        }
        logDecision("$where $reason")
        return result
    }

    private fun resetCounters() {
        verifiedCount = 0
        gesturesDispatched = 0
        swipesDispatched = 0
        swipesVerified = 0
        playSkip = 0
        unknownMoves = 0
        menuStreak = 0
        menuSinceMs = 0L
        zeroCircleReads = 0
        sawBrightCircles = false
        circleBaseline = null
        circleReadingChanged = false
        soloEvidence = false
        opponentBarStreak = 0
        lastHelpAttemptAtMs = 0L
        lastCircleSequence = -1L
        unchangedRetries = 0
        ownGestureOpen = false
        ownPath = null
        lastGestureAtMs = 0L
        boosterWindowOpen = false
        boosterWindowUntilMs = 0L
        pauseUntilMs = 0L
        outsideTimes.clear()
        outsideLog.clear()
        idleLog.clear()
        lastIdleLogAtMs = 0L
        lastBlockLogAtMs = 0L
        extraFollowUpUntilMs = 0L
        swipeOverride = false
        decisionLog.clear()
        screenMode = PlayMode.SOLO
        latestClassified = PlayMode.SOLO
        pendingMode = null
        pendingModeFrames = 0
        lastHudKind = PlayGate.OURS
        lastOpponentAtMs = 0L
        zeroTimeReads = 0
        previousCapturedAtMs = Long.MAX_VALUE
        timeLeftSeconds = null
        multiplier = null
        opponentWait = false
        helpsUsedThisTurn = 0
        helpQuotaHeld = false
        helpPhase = "idle"
        helpId = null
        helpStartedAtMs = 0L
        helpTargetX = Float.NaN
        helpTargetY = Float.NaN
        equippedBoosterId = null
        boosterTargetNote = "booster target=unverified equipped=unknown"
        helpNote = "helps unread"
    }

    private fun closeOpen(open: OpenMove, sample: SettleSample, verification: String) {
        if (!open.countsAsSwipe) endBoosterWindow(sample.nowMs)
        record(
            number = open.number,
            cells = open.cells,
            fromX = open.fromX,
            fromY = open.fromY,
            toX = open.toX,
            toY = open.toY,
            callback = open.callback,
            verification = verification,
            beforeUnknown = open.beforeUnknown,
            afterUnknown = sample.unknownCount,
            startedAtMs = open.startedAtMs,
            finishedAtMs = sample.nowMs,
            ignoredTransient = open.ignoredCount,
            ignoredReasons = open.ignoredReasons.joinToString("; "),
            userInterference = open.outsideTouches > 0 || verification.contains("user interference"),
            outsideTouches = open.outsideTouches,
            boardKeptChanging = open.sawBoardChange,
            playExport = open.playExport,
            matchLen = open.matchLen,
            extraMove = open.extraMove,
            blueCleared = open.blueCleared,
            totalCleared = open.totalCleared,
            playUncertain = open.playUncertain,
        )
        openMove = null
    }

    private fun record(
        number: Int,
        cells: String,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        callback: String,
        verification: String,
        beforeUnknown: Int,
        afterUnknown: Int,
        startedAtMs: Long,
        finishedAtMs: Long,
        ignoredTransient: Int = 0,
        ignoredReasons: String = "",
        userInterference: Boolean = false,
        outsideTouches: Int = 0,
        boardKeptChanging: Boolean = false,
        playExport: String = "",
        matchLen: Int = 0,
        extraMove: Boolean = false,
        blueCleared: Int = 0,
        totalCleared: Int = 0,
        playUncertain: Boolean = false,
    ) {
        moves += MoveRecord(
            number = number,
            cells = cells,
            fromX = fromX,
            fromY = fromY,
            toX = toX,
            toY = toY,
            callback = callback,
            verification = verification,
            beforeUnknown = beforeUnknown,
            afterUnknown = afterUnknown,
            startedAtMs = startedAtMs,
            finishedAtMs = finishedAtMs,
            durationMs = (finishedAtMs - startedAtMs).coerceAtLeast(0L),
            ignoredTransient = ignoredTransient,
            ignoredReasons = ignoredReasons,
            userInterference = userInterference,
            outsideTouches = outsideTouches,
            boardKeptChanging = boardKeptChanging,
            playExport = playExport,
            matchLen = matchLen,
            extraMove = extraMove,
            blueCleared = blueCleared,
            totalCleared = totalCleared,
            playUncertain = playUncertain,
        )
    }

    private fun stop(nowMs: Long, reason: String): Decision.Stop {
        if (phase != Phase.STOPPED) {
            phase = Phase.STOPPED
            stopReason = reason
            stoppedAtMs = nowMs
            outstanding = null
        }
        return Decision.Stop(stopReason)
    }

    companion object {
        const val MAX_MOVES = 120
        const val SESSION_LIMIT_MS = 1_800_000L
        const val MODE_CONFIRM_FRAMES = 3
        const val IDLE_RING = 80
        const val HELP_ATTEMPT_GAP_MS = 30_000L
        const val OPPONENT_BAR_FRAMES = 3
        const val HELP_PROMPT_MS = 4_000L
        const val HELP_SETTLE_MS = 8_000L
        const val BOOSTER_TAP_MS = 120L
        const val BOOSTER_MAX_ATTEMPTS = 3
        const val BOOSTER_CONFIRM_BOARDS = 2
        const val BOOSTER_HANDLING_BUDGET_MS = 10_000L
        const val EXTRA_FOLLOW_UP_MS = 10_000L
        const val MENU_FRAMES = 3
        const val MENU_HOLD_MS = 3_000L
        const val PER_MOVE_BUDGET_MS = 20_000L
        const val SETTLE_WAIT_MS = 60_000L
        const val POST_SWIPE_MS = 2_500L
        const val POST_SWIPE_BIG_MS = 4_000L
        const val PLAYABLE_GAP_MS = 250L
        const val PLAY_UNKNOWN_LIMIT = 3
        const val LANDED_WINDOW_MS = 6_000L
        const val BOOSTER_PLAYABLE_MS = 15_000L
        const val IDLE_WITHOUT_GESTURE_MS = 20_000L
        const val IDLE_LOG_EVERY_MS = 5_000L
        const val SETTLE_FRAME_AGE_MS = 5_000L
        const val NOMINAL_FRAME_MS = 200L
        const val POLL_STEP_MS = 80L
        const val UNCHANGED_MIN_MS = 1_500L
        const val STABLE_FRACTION = 0.02f

        /** Longer waits on a slow camera, at most twice nominal. The 60 s cap does not grow. */
        fun scaled(baseMs: Long, cadenceMs: Long): Long {
            if (cadenceMs <= NOMINAL_FRAME_MS) return baseMs
            val factor = (cadenceMs.toDouble() / NOMINAL_FRAME_MS.toDouble()).coerceAtMost(2.0)
            return (baseMs * factor).toLong().coerceAtLeast(baseMs)
        }
        const val OWN_GESTURE_AFTER_MS = 1_500L
        const val OWN_GESTURE_RADIUS_PX = 60f
        const val OUTSIDE_PAUSE_MS = 3_000L
        const val OUTSIDE_CLUSTER_MS = 10_000L
        const val MIN_POST_COLLAPSE_MS = 2_000L
        const val MAX_UNKNOWN = 1
        private const val MAX_IGNORED_LINES = 200

        const val NEED_SELF_CHECK_HU =
            "TESZT ÉRINTÉS: érintsd a fehér kalibrációs pontot. " +
                "Csak MEASURED_WITHIN_TOLERANCE után nyomd meg a 10 LÉPÉS TESZT-et. " +
                "Nincs játékérintés."
    }
}

/**
 * Coarse ROI sample used to see that a swap animation has stopped.
 * It does not run vision and it does not change a gate.
 */
object BoardStability {
    fun signature(
        pixels: IntArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): IntArray {
        if (width <= 0 || height <= 0 || pixels.size != width * height) return IntArray(0)
        val l = left.coerceIn(0, width - 1)
        val t = top.coerceIn(0, height - 1)
        val r = right.coerceIn(l + 1, width)
        val b = bottom.coerceIn(t + 1, height)
        val cols = 48
        val rows = 48
        val out = IntArray(cols * rows)
        var i = 0
        for (row in 0 until rows) {
            val y = t + (row.toLong() * (b - t) / rows).toInt().coerceIn(0, height - 1)
            for (col in 0 until cols) {
                val x = l + (col.toLong() * (r - l) / cols).toInt().coerceIn(0, width - 1)
                out[i++] = pixels[y * width + x]
            }
        }
        return out
    }

    fun changedFraction(before: IntArray, after: IntArray): Float {
        if (before.isEmpty() || before.size != after.size) return 1f
        var changed = 0
        for (i in before.indices) {
            if (before[i] != after[i]) changed++
        }
        return changed.toFloat() / before.size.toFloat()
    }
}
