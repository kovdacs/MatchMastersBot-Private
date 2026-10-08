package com.match3vision.analyzer.input

/**
 * Controlled phone test: at most five real moves, then stop.
 *
 * Move 1 is a probe. Moves 2–5 are issued only after move 1 verifies.
 * The same checks apply to every move. A failed check stops the session.
 * The same move is not retried. Five gestures is the maximum.
 *
 * The clock starts at [arm] (the 5 LÉPÉS TESZT press, after the self-check).
 * All five verified moves must finish inside [SESSION_LIMIT_MS].
 * Each gesture has [PER_MOVE_BUDGET_MS] for the swipe and the settle.
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
    )

    val isActive: Boolean
        get() = phase == Phase.RUNNING || phase == Phase.SETTLING

    fun label(): String = "5 LÉPÉS TESZT: $verifiedCount/$MAX_MOVES"

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
        return true
    }

    fun noteStartExport(text: String) {
        startExport = text.trim()
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
            return abort("STOP — 60s session limit", nowMs)
        }
        return null
    }

    fun requestDispatch(gates: Gates): Decision {
        if (phase == Phase.STOPPED) return Decision.Stop(stopReason.ifBlank { "stopped" })
        if (phase == Phase.IDLE) return Decision.Stop("5 LÉPÉS not armed")
        if (phase == Phase.SETTLING) return Decision.Hold("settle in progress")
        sessionLimit(gates.nowMs)?.let { return it }
        immediateAbort(gates.nowMs, gates.ownUi, gates.a11yConnected, gates.roiPlausible)?.let { return it }
        if (gesturesDispatched >= MAX_MOVES || verifiedCount >= MAX_MOVES) {
            return stop(gates.nowMs, "STOP — 5 moves complete")
        }
        if (outstanding != null) return Decision.Hold("dispatch permit already issued")
        val hold = holdReason(gates)
        if (hold != null) return Decision.Hold(hold)
        val permit = Permit(token = nextToken++, moveNumber = gesturesDispatched + 1)
        outstanding = permit
        return Decision.Go(permit)
    }

    fun consumePermit(permit: Permit): Boolean {
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

    fun noteGesture(fact: GestureFact): Decision {
        if (phase != Phase.RUNNING) return Decision.Stop(stopReason.ifBlank { "not running" })
        sessionLimit(fact.nowMs)?.let { return it }
        if (gesturesDispatched >= MAX_MOVES) {
            return stop(fact.nowMs, "STOP — max 5 gestures")
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
        )
        phase = Phase.SETTLING
        return Decision.Hold("settling move $gesturesDispatched")
    }

    fun onSettle(sample: SettleSample): Decision {
        val open = openMove
        if (phase != Phase.SETTLING || open == null) {
            return Decision.Stop(stopReason.ifBlank { "not settling" })
        }
        immediateAbort(
            sample.nowMs,
            sample.ownUi,
            sample.a11yConnected,
            sample.roiPlausible,
        )?.let { decision ->
            closeOpen(open, sample, "FAILED — ${decision.reason}")
            return decision
        }
        if (sample.nowMs - startedAtMs >= SESSION_LIMIT_MS) {
            closeOpen(open, sample, "FAILED — 60s session limit")
            return stop(sample.nowMs, "STOP — 60s session limit")
        }
        if (sample.nowMs - open.startedAtMs >= PER_MOVE_BUDGET_MS) {
            closeOpen(open, sample, "FAILED — per-move budget ${PER_MOVE_BUDGET_MS}ms")
            return stop(sample.nowMs, "STOP — per-move budget ${PER_MOVE_BUDGET_MS}ms")
        }
        val stable = sample.diffFraction != null && sample.diffFraction <= STABLE_FRACTION
        if (!stable) return Decision.Hold("board still moving")
        val changed = sample.boardHash != open.beforeHash
        if (!changed) {
            if (sample.nowMs - open.startedAtMs < UNCHANGED_MIN_MS) {
                return Decision.Hold("waiting to see the board change")
            }
            closeOpen(open, sample, "FAILED — board unchanged")
            return stop(sample.nowMs, "STOP — board unchanged after move ${open.number}")
        }
        if (!sample.frameFresh || !sample.visionPass) {
            return Decision.Hold("board changed; waiting for a fresh vision PASS")
        }
        closeOpen(open, sample, "PASS — callback completed, board changed, fresh, ROI plausible, vision PASS")
        verifiedCount += 1
        openMove = null
        if (verifiedCount >= MAX_MOVES) {
            return stop(sample.nowMs, "STOP — 5 moves verified")
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
            )
            openMove = null
        }
        return stop(nowMs, reason)
    }

    fun report(): String = buildString {
        if (startExport.isNotBlank()) {
            appendLine(startExport.trimEnd())
        }
        appendLine("--- 5 LÉPÉS TESZT ---")
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
        appendLine("stop=${stopReason.ifBlank { "none" }}")
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
        }
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
        else -> null
    }

    private fun sessionLimit(nowMs: Long): Decision.Stop? {
        if (phase != Phase.RUNNING && phase != Phase.SETTLING) return null
        if (nowMs - startedAtMs >= SESSION_LIMIT_MS) {
            return stop(nowMs, "STOP — 60s session limit")
        }
        return null
    }

    private fun immediateAbort(
        nowMs: Long,
        ownUi: Boolean,
        a11yConnected: Boolean,
        roiPlausible: Boolean,
    ): Decision.Stop? = when {
        ownUi -> stop(nowMs, "STOP — our app is in the foreground")
        !a11yConnected -> stop(nowMs, "STOP — accessibility lost")
        !roiPlausible -> stop(nowMs, "STOP — ROI implausible")
        else -> null
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
        const val MAX_MOVES = 5
        const val SESSION_LIMIT_MS = 60_000L
        const val PER_MOVE_BUDGET_MS = 12_000L
        const val POLL_STEP_MS = 80L
        const val UNCHANGED_MIN_MS = 1_500L
        const val STABLE_FRACTION = 0.02f
        const val MIN_POST_COLLAPSE_MS = 2_000L

        const val NEED_SELF_CHECK_HU =
            "TESZT ÉRINTÉS: érintsd a fehér kalibrációs pontot. " +
                "Csak MEASURED_WITHIN_TOLERANCE után nyomd meg az 5 LÉPÉS TESZT-et. " +
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
