package com.match3vision.analyzer.input

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.moves.PlayMoveRanker
import kotlin.math.hypot

/**
 * Plays while bright move circles remain. A hard cap stops the session at
 * [MAX_MOVES] gestures or [SESSION_LIMIT_MS].
 *
 * Move 1 is a probe. Later moves are issued only after the previous one verifies.
 * The same checks apply to every move. A failed check stops the session.
 * The same move is not retried.
 *
 * The clock starts at [arm] (the 10 LÉPÉS TESZT press, after the self-check).
 * All verified moves must finish inside [SESSION_LIMIT_MS].
 * A swipe is never abandoned at 20 s. The next swipe waits for a playable
 * board: two vision PASS frames whose known labels differ in at most one
 * cell, at least [PLAYABLE_GAP_MS] apart (or one measured frame interval,
 * when that is longer), both captured after the swipe, both at least
 * [POST_SWIPE_MS] after it ( [POST_SWIPE_BIG_MS] after a 4+ clear or a
 * special). The wait and the gap use capture time. Frame age up to
 * [SETTLE_FRAME_AGE_MS] is accepted. Those waits scale with the measured
 * capture interval, at most twice the nominal wait. The wait itself never
 * sends a gesture. If the
 * board is still not playable after [SETTLE_WAIT_MS], the session stops.
 *
 * This type does not run vision and does not dispatch a gesture.
 */
class FiveMoveSession {
    enum class Phase { IDLE, RUNNING, SETTLING, STOPPED }

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

    /** One ACTIVATE attempt per session. A miss does not stop gem play. */
    var boosterLatched: Boolean = false
        private set

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
    )

    private var unknownMoves: Int = 0
    private var menuStreak: Int = 0
    private var menuSinceMs: Long = 0L
    private var zeroCircleReads: Int = 0
    private var lastCircleSequence: Long = -1L
    private var unchangedRetries: Int = 0
    private val decisionLog = ArrayList<String>()

    val isActive: Boolean
        get() = phase == Phase.RUNNING || phase == Phase.SETTLING

    fun label(): String = "10 LÉPÉS TESZT: $verifiedCount"

    /**
     * Zero bright circles stop only after two classifiable reads on a playable
     * board. One empty read, or a row that was not classified, does not stop.
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
        if (frameSequence == lastCircleSequence) return null
        lastCircleSequence = frameSequence
        if (bright > 0) {
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
     * frame clears that streak. An unknown HUD (neither solo-positive nor PvP
     * Your Turn) stops after two such moves. A dimmed transition is ignored.
     * Solo never increments the unknown streak.
     */
    fun notePlayHud(kind: String, nowMs: Long): Decision? {
        if (phase != Phase.RUNNING && phase != Phase.SETTLING) return null
        val decision: Decision? = when (kind) {
            PlayGate.OPPONENT -> abort("STOP — Opponent's Turn", nowMs)
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
            PlayGate.UNKNOWN ->
                if (unknownMoves >= 2) abort("STOP — unknown HUD", nowMs) else null
            else -> {
                unknownMoves = 0
                if (kind == PlayGate.OURS) {
                    menuStreak = 0
                    menuSinceMs = 0L
                }
                null
            }
        }
        if (decision != null) return decided(decision, "hud")
        logDecision("hud $kind")
        return null
    }

    /** Call when a swipe is actually sent, so the unknown streak counts moves. */
    fun noteDispatchedHud(kind: String) {
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
        boosterLatched = false
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
        boosterLatched = false
        autoProbe = false
        pendingAutoSave = false
    }

    /** Ignore ACTION_OUTSIDE that belongs to the swipe we just injected. */
    fun suppressOutsideTouchUntil(untilMs: Long) {
        if (untilMs > suppressOutsideUntilMs) suppressOutsideUntilMs = untilMs
    }

    /**
     * Our swipe or ACTIVATE tap is in flight. Outside events are ours until
     * [finishOwnGesture]. [x1]..[y2] is the tap point or the swipe segment.
     */
    fun beginOwnGesture() {
        ownGestureOpen = true
    }

    fun beginOwnGesture(x1: Float, y1: Float, x2: Float, y2: Float) {
        if (x1.isFinite() && y1.isFinite() && x2.isFinite() && y2.isFinite()) {
            ownPath = OwnPath(x1, y1, x2, y2)
        }
        ownGestureOpen = true
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
     * Board-diff verification cannot tell that finger from our gesture, so the
     * open move is not counted. Our own tap and swipe are not a finger.
     */
    fun noteOutsideTouch(nowMs: Long, x: Float = Float.NaN, y: Float = Float.NaN): Decision? {
        if (phase != Phase.RUNNING && phase != Phase.SETTLING) return null
        if (ownGestureOpen || nowMs < suppressOutsideUntilMs) return null
        if (nearOwnGesture(x, y)) return null
        outsideTouches += 1
        openMove?.let { it.outsideTouches += 1 }
        return abort("STOP — user interference", nowMs)
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
        if (outsideTouches > 0) return abort("STOP — user interference", nowMs)
        if (nowMs - startedAtMs >= SESSION_LIMIT_MS) {
            return abort(sessionLimitText(), nowMs)
        }
        return null
    }

    fun requestDispatch(gates: Gates): Decision {
        if (phase == Phase.STOPPED) return Decision.Stop(stopReason.ifBlank { "stopped" })
        if (phase == Phase.IDLE) return Decision.Stop("10 LÉPÉS not armed")
        if (phase == Phase.SETTLING) return Decision.Hold("settle in progress")
        sessionLimit(gates.nowMs)?.let { return it }
        if (outsideTouches > 0) return stop(gates.nowMs, "STOP — user interference")
        immediateAbort(gates.nowMs, gates.ownUi, gates.a11yConnected)?.let { return it }
        if (gesturesDispatched >= MAX_MOVES || verifiedCount >= MAX_MOVES) {
            return stop(gates.nowMs, "STOP — 40 gesture safety cap")
        }
        if (outstanding != null) return Decision.Hold("dispatch permit already issued")
        val hold = holdReason(gates)
        if (hold != null) return Decision.Hold(hold)
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
        boosterLatched = true
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
                stop(nowMs, "STOP — 40 gesture safety cap")
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
    ): Decision {
        boosterLatched = true
        if (phase != Phase.RUNNING) return Decision.Stop(stopReason.ifBlank { "not running" })
        sessionLimit(nowMs)?.let { return it }
        if (gesturesDispatched >= MAX_MOVES) {
            return stop(nowMs, "STOP — 40 gesture safety cap")
        }
        gesturesDispatched += 1
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
            callback = "onCompleted",
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
            return stop(fact.nowMs, "STOP — 40 gesture safety cap")
        }
        gesturesDispatched += 1
        swipesDispatched += 1
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
            return stop(fact.nowMs, "STOP — gesture callback was not completed")
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
        if (outsideTouches > 0) {
            closeOpen(open, sample, "FAILED — user interference")
            return decided(stop(sample.nowMs, "STOP — user interference"), "settle")
        }
        if (sample.nowMs - startedAtMs >= SESSION_LIMIT_MS) {
            closeOpen(open, sample, "FAILED — ${SESSION_LIMIT_MS / 1_000}s session limit")
            return decided(stop(sample.nowMs, sessionLimitText()), "settle")
        }
        if (!sample.countBoardChange) {
            val changed = sample.boardHash != open.beforeHash
            val verification = if (changed) {
                "FAILED — board changed during the opponent's turn"
            } else {
                "FAILED — opponent turn before the move was verified"
            }
            closeOpen(open, sample, verification)
            return decided(stop(sample.nowMs, "STOP — opponent turn during verification"), "settle")
        }
        if (sample.diffFraction != null && sample.diffFraction > STABLE_FRACTION) {
            open.sawBoardChange = true
        }
        if (sample.nowMs - open.startedAtMs >= SETTLE_WAIT_MS) {
            closeOpen(open, sample, "FAILED — settle wait ${SETTLE_WAIT_MS}ms")
            return decided(stop(sample.nowMs, "STOP — settle wait ${SETTLE_WAIT_MS}ms"), "settle")
        }
        if (sample.dimmed) return ignore(open, "dimmed transition")
        val ageLimit = maxOf(SETTLE_FRAME_AGE_MS, sample.cadenceMedianMs * 3L)
        val age = when {
            sample.frameAgeMs > 0L -> sample.frameAgeMs
            !sample.frameFresh -> ageLimit + 1L
            else -> 0L
        }
        if (age > ageLimit) return ignore(open, "frame not fresh")
        if (!sample.overlayOutside) return ignore(open, "overlay on the board")
        if (!sample.roiPlausible) return ignore(open, "implausible ROI")
        if (!sample.visionPass || sample.unknownCount > MAX_UNKNOWN) {
            return ignore(open, "vision HOLD unk=${sample.unknownCount}")
        }
        if (!sample.capturedAfterGesture || sample.frameSequence <= open.swipeSequence) {
            return ignore(open, "frame is from before the gesture")
        }
        val captured = sample.nowMs - age.coerceAtLeast(0L)
        val post = scaled(if (open.longSettle) POST_SWIPE_BIG_MS else POST_SWIPE_MS, sample.cadenceMedianMs)
        if (captured - open.startedAtMs < post) {
            return ignore(open, "waiting ${post}ms after the swipe")
        }
        val labels = sample.labelHash ?: sample.boardHash
        val gap = maxOf(PLAYABLE_GAP_MS, sample.cadenceMedianMs)
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
            return ignore(open, "first stable label pixelDiff=${sample.diffFraction ?: "none"}")
        }
        if (sample.frameSequence <= open.anchorSeq || captured - open.anchorMs < gap) {
            return ignore(open, "label pair too close")
        }
        val labelsDiffer = labels != open.beforeLabel
        val drop = open.beforeCircles != null &&
            sample.circlesClassifiable &&
            sample.circlesBright != null &&
            sample.circlesBright == open.beforeCircles!! - 1
        val circlesSame = open.beforeCircles != null &&
            sample.circlesClassifiable &&
            sample.circlesBright == open.beforeCircles
        val spent = noteCircles(
            classifiable = sample.circlesClassifiable,
            bright = sample.circlesBright,
            playable = true,
            nowMs = sample.nowMs,
            frameSequence = sample.frameSequence,
        )
        if (labelsDiffer || drop) {
            if (autoProbe && swipesVerified == 0 && !sample.swapOverlaps) {
                closeOpen(open, sample, "FAILED — auto-calibration missed the swapped cells")
                return decided(stop(sample.nowMs, AutoCalibration.STOP_MISSED), "settle")
            }
            if (sample.frameSequence > lastSettledFrameSequence) {
                lastSettledFrameSequence = sample.frameSequence
            }
            val extraNote = if (circlesSame && labelsDiffer) " extra-move circles unchanged" else ""
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
                return decided(stop(sample.nowMs, "STOP — 40 gesture safety cap"), "settle")
            }
            phase = Phase.RUNNING
            return decided(Decision.Hold("move $verifiedCount verified$extraNote"), "settle")
        }
        if (open.playableSinceMs == 0L) open.playableSinceMs = sample.nowMs
        val landed = scaled(LANDED_WINDOW_MS, sample.cadenceMedianMs)
        if (sample.nowMs - open.playableSinceMs < landed) {
            return ignore(open, "confirming the board did not change")
        }
        if (unchangedRetries >= 1) {
            closeOpen(open, sample, "FAILED — board unchanged")
            return decided(stop(sample.nowMs, "STOP — board unchanged after move ${open.number}"), "settle")
        }
        unchangedRetries = 1
        playSkip = 1
        closeOpen(open, sample, "RETRY — board unchanged, next move")
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
        appendLine(hudTrace.ifBlank { "hudState=UNKNOWN" })
        appendLine("outsideTouches=$outsideTouches")
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
            sample.unknownCount <= MAX_UNKNOWN

    private fun transientReason(sample: SettleSample): String = when {
        !sample.capturedAfterGesture -> "frame is from before the gesture"
        !sample.frameFresh -> "frame not fresh"
        !sample.overlayOutside -> "overlay on the board"
        !sample.roiPlausible -> "implausible ROI"
        !sample.visionPass -> "vision HOLD unk=${sample.unknownCount}"
        sample.unknownCount > MAX_UNKNOWN -> "unk=${sample.unknownCount}"
        else -> "not a PASS frame"
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
        lastCircleSequence = -1L
        unchangedRetries = 0
        ownGestureOpen = false
        ownPath = null
        decisionLog.clear()
    }

    private fun closeOpen(open: OpenMove, sample: SettleSample, verification: String) {
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
        const val MAX_MOVES = 40
        const val SESSION_LIMIT_MS = 600_000L
        const val MENU_FRAMES = 3
        const val MENU_HOLD_MS = 3_000L
        const val PER_MOVE_BUDGET_MS = 20_000L
        const val SETTLE_WAIT_MS = 60_000L
        const val POST_SWIPE_MS = 2_500L
        const val POST_SWIPE_BIG_MS = 4_000L
        const val PLAYABLE_GAP_MS = 300L
        const val LANDED_WINDOW_MS = 6_000L
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
