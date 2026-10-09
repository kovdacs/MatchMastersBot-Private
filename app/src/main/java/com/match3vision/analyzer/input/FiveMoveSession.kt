package com.match3vision.analyzer.input

import com.match3vision.analyzer.moves.PlayMoveRanker

/**
 * Controlled phone test: at most ten real moves, then stop.
 *
 * Move 1 is a probe. Later moves are issued only after the previous one verifies.
 * The same checks apply to every move. A failed check stops the session.
 * The same move is not retried. Ten gestures is the maximum.
 *
 * The clock starts at [arm] (the 10 LÉPÉS TESZT press, after the self-check).
 * All verified moves must finish inside [SESSION_LIMIT_MS].
 * Each gesture has [PER_MOVE_BUDGET_MS] for the swipe and the settle.
 * After a gesture, HOLD, implausible-ROI, and unstable frames are ignored
 * until a stable PASS frame shows a real board change, or the settle budget ends.
 * A budget that expires while the board is still changing is CHANGED_UNSETTLED.
 * That move is not verified, and no later gesture is issued.
 * A stable PASS board that still matches the pre-move board for 1.5 s stops.
 * Settle is a frame-diff, not a fixed sleep.
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

    /** One ACTIVATE attempt per session. A miss does not stop gem play. */
    var boosterLatched: Boolean = false
        private set

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

    /** Sequence of the frame that verified the previous move. 0 until then. */
    private var lastSettledFrameSequence: Long = 0L

    /** Reused calibration line, included in the export when set. */
    private var calibrationLine: String = ""

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
        var ignoredCount: Int = 0,
        val ignoredReasons: ArrayList<String> = ArrayList(),
        var outsideTouches: Int = 0,
        var sawBoardChange: Boolean = false,
    )

    val isActive: Boolean
        get() = phase == Phase.RUNNING || phase == Phase.SETTLING

    fun label(): String = "10 LÉPÉS TESZT: $verifiedCount/$MAX_MOVES"

    fun movesSnapshot(): List<MoveRecord> = moves.toList()

    /** Earlier of the session limit and the open move's budget. */
    fun activeDeadlineMs(): Long {
        val sessionEnd = if (startedAtMs > 0L) startedAtMs + SESSION_LIMIT_MS else Long.MAX_VALUE
        val moveEnd = openMove?.let { it.startedAtMs + PER_MOVE_BUDGET_MS } ?: sessionEnd
        return minOf(sessionEnd, moveEnd)
    }

    fun arm(nowMs: Long): Boolean {
        if (isActive) return false
        phase = Phase.RUNNING
        verifiedCount = 0
        gesturesDispatched = 0
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
        gameLines.clear()
        pendingGameLog = null
        boosterLatched = false
        return true
    }

    fun noteStartExport(text: String) {
        startExport = text.trim()
    }

    fun noteCalibration(text: String) {
        calibrationLine = text.trim()
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
        verifiedCount = 0
        gesturesDispatched = 0
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
        gameLines.clear()
        pendingGameLog = null
        boosterLatched = false
    }

    /** Ignore ACTION_OUTSIDE that belongs to the swipe we just injected. */
    fun suppressOutsideTouchUntil(untilMs: Long) {
        if (untilMs > suppressOutsideUntilMs) suppressOutsideUntilMs = untilMs
    }

    /**
     * A finger landed outside the bubble while this session was running.
     * Board-diff verification cannot tell that finger from our gesture, so the
     * open move is not counted.
     */
    fun noteOutsideTouch(nowMs: Long): Decision? {
        if (phase != Phase.RUNNING && phase != Phase.SETTLING) return null
        if (nowMs < suppressOutsideUntilMs) return null
        outsideTouches += 1
        openMove?.let { it.outsideTouches += 1 }
        return abort("STOP — user interference", nowMs)
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
            return abort("STOP — 120s session limit", nowMs)
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
            return stop(gates.nowMs, "STOP — 10 moves complete")
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
    ): String {
        boosterLatched = true
        if (phase != Phase.RUNNING) return "not running"
        if (gesturesDispatched >= MAX_MOVES) return "cap"
        gesturesDispatched += 1
        val verification = when {
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
        if (changed && stable && callbackCompleted) {
            verifiedCount += 1
            if (verifiedCount >= MAX_MOVES) {
                stop(nowMs, "STOP — 10 moves verified")
            }
        }
        return verification
    }

    fun noteGesture(fact: GestureFact): Decision {
        if (phase != Phase.RUNNING) return Decision.Stop(stopReason.ifBlank { "not running" })
        sessionLimit(fact.nowMs)?.let { return it }
        if (gesturesDispatched >= MAX_MOVES) {
            return stop(fact.nowMs, "STOP — max 10 gestures")
        }
        gesturesDispatched += 1
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
            )
        phase = Phase.SETTLING
        return Decision.Hold("settling move $gesturesDispatched")
    }

    fun onSettle(sample: SettleSample): Decision {
        val open = openMove
        if (phase != Phase.SETTLING || open == null) {
            return Decision.Stop(stopReason.ifBlank { "not settling" })
        }
        immediateAbort(sample.nowMs, sample.ownUi, sample.a11yConnected)?.let { decision ->
            closeOpen(open, sample, "FAILED — ${decision.reason}")
            return decision
        }
        if (outsideTouches > 0) {
            closeOpen(open, sample, "FAILED — user interference")
            return stop(sample.nowMs, "STOP — user interference")
        }
        if (sample.nowMs - startedAtMs >= SESSION_LIMIT_MS) {
            closeOpen(open, sample, "FAILED — 120s session limit")
            return stop(sample.nowMs, "STOP — 120s session limit")
        }
        if (sample.diffFraction != null && sample.diffFraction > STABLE_FRACTION) {
            open.sawBoardChange = true
        }
        if (sample.nowMs - open.startedAtMs >= PER_MOVE_BUDGET_MS) {
            val verification = if (open.sawBoardChange) {
                "CHANGED_UNSETTLED — gesture sent, board kept changing, not a stable PASS"
            } else {
                "FAILED — per-move budget ${PER_MOVE_BUDGET_MS}ms"
            }
            closeOpen(open, sample, verification)
            return stop(sample.nowMs, "STOP — per-move settle budget ${PER_MOVE_BUDGET_MS}ms")
        }
        if (!qualifiesAsPass(sample)) {
            return ignore(open, transientReason(sample))
        }
        val stable = sample.diffFraction != null && sample.diffFraction <= STABLE_FRACTION
        if (!stable) {
            val detail = if (sample.diffFraction == null) "no pair yet" else "diff=${sample.diffFraction}"
            return ignore(open, "unstable ($detail)")
        }
        val changed = sample.boardHash != open.beforeHash
        if (!changed) {
            if (sample.nowMs - open.startedAtMs < UNCHANGED_MIN_MS) {
                return Decision.Hold("waiting to see the board change")
            }
            closeOpen(open, sample, "FAILED — board unchanged")
            return stop(sample.nowMs, "STOP — board unchanged after move ${open.number}")
        }
        if (sample.frameSequence > lastSettledFrameSequence) {
            lastSettledFrameSequence = sample.frameSequence
        }
        closeOpen(
            open,
            sample,
            "PASS — callback completed, board changed, stable, fresh, ROI plausible, vision PASS",
        )
        verifiedCount += 1
        openMove = null
        if (verifiedCount >= MAX_MOVES) {
            return stop(sample.nowMs, "STOP — 10 moves verified")
        }
        phase = Phase.RUNNING
        return Decision.Hold("move $verifiedCount verified")
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
        appendLine("perMoveBudgetMs=$PER_MOVE_BUDGET_MS")
        appendLine("pollStepMs=$POLL_STEP_MS")
        appendLine("stableFraction<=$STABLE_FRACTION")
        appendLine("unchangedMinMs=$UNCHANGED_MIN_MS")
        appendLine(
            "expected: up to $MAX_MOVES verified moves, each within " +
                "${PER_MOVE_BUDGET_MS}ms, all inside ${SESSION_LIMIT_MS}ms from the button press",
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
        appendLine("verified=$verifiedCount/$MAX_MOVES gestures=$gesturesDispatched")
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
                    "settleBudgetMs=$PER_MOVE_BUDGET_MS",
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
        appendLine("--- GAME LOG ---")
        appendLine("format=jsonl")
        appendLine("personalData=none")
        val log = gameLogText()
        if (log.isBlank()) appendLine("moves: none") else append(log)
    }

    private fun holdReason(gates: Gates): String? = when {
        !gates.selfCheckMeasured ->
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
            return stop(nowMs, "STOP — 120s session limit")
        }
        return null
    }

    private fun immediateAbort(
        nowMs: Long,
        ownUi: Boolean,
        a11yConnected: Boolean,
    ): Decision.Stop? = when {
        ownUi -> stop(nowMs, "STOP — our app is in the foreground")
        !a11yConnected -> stop(nowMs, "STOP — accessibility lost")
        else -> null
    }

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
        return Decision.Hold("settling — $reason")
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
        const val MAX_MOVES = 10
        const val SESSION_LIMIT_MS = 120_000L
        const val PER_MOVE_BUDGET_MS = 20_000L
        const val POLL_STEP_MS = 80L
        const val UNCHANGED_MIN_MS = 1_500L
        const val STABLE_FRACTION = 0.02f
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
