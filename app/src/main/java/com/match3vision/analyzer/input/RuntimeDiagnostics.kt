package com.match3vision.analyzer.input

import com.match3vision.analyzer.capture.FrameSequenceGate

/**
 * Pure runtime snapshot for the bubble. Every field is filled from an
 * observation the caller measured — this type does not invent PASS, a
 * connected accessibility service, or VERIFY SUCCESS.
 *
 * Dispatch success and verification success are different facts.
 * [VerificationPolicy] never promotes a `dispatchGesture` result to SUCCESS.
 */
data class RuntimeCycleContext(
    /** [MatchMastersAccessibilityService.isConnected] — not the settings flag. */
    val a11yConnected: Boolean,
    /** CaptureService process is alive (manager present), not a sticky UI flag. */
    val captureOn: Boolean,
    val hasFrame: Boolean,
    val frameAgeMs: Long,
    val frameSequenceDecision: FrameSequenceGate.Decision? = null,
    /**
     * Independent display size in pixels. Not copied from the capture bitmap.
     * [screenSource] names the measurement. Matching [frameWidth] does not
     * prove coordinate-origin alignment ([coordinateAlignmentProven] stays false).
     */
    val screenWidth: Int,
    val screenHeight: Int,
    val frameTimestampMs: Long = -1L,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    /** True only for the JVM continuous harness. Never set on the device loop. */
    val simulated: Boolean = false,
    /**
     * [android.os.SystemClock.elapsedRealtime] when the frame was captured.
     * 0 when the caller did not measure it. The dispatcher remeasures age
     * from this. A missing sample fails closed.
     */
    val capturedElapsedMs: Long = 0L,
    /** Independent display measurement source. Never "frame". */
    val screenSource: String = "",
    val screenRotation: Int = -1,
    /**
     * Always false. Matching width and height do not prove that capture pixels
     * and accessibility gesture coordinates share an origin.
     */
    val coordinateAlignmentProven: Boolean = false,
    /** Capture sequence of the frame this plan was built from. -1 if unknown. */
    val frameSequence: Long = -1L,
    /**
     * Extra origin shift a caller asks to apply on top of full-frame pixels.
     * Non-zero is an inset/offset mismatch. [DisplayInsetPolicy] refuses it.
     * Production passes 0. These values are not added to the gesture.
     */
    val originOffsetX: Int = 0,
    val originOffsetY: Int = 0,
    /**
     * Oldest frame the dispatcher may act on. The five-move loop sets this to
     * the settle age limit so a frame that just verified is not refused.
     */
    val maxFrameAgeMs: Long = GestureFailSafe.MAX_FRAME_AGE_MS,
) {
    init {
        require(!coordinateAlignmentProven) {
            "coordinate alignment is not proven by matching dimensions"
        }
    }
}

/**
 * What the verifier is allowed to look at.
 *
 * [newFrameAccepted] and [frameFresh] must be derived from a frame sequence
 * and a monotonic clock. Missing, zero, or backwards timestamps fail closed.
 * [gestureEligible] is true only after the gesture callback completed.
 */
data class VerifyObservation(
    val newFrameAccepted: Boolean,
    val frameFresh: Boolean,
    val reason: String = "",
    /** Wall time of the frame. -1 when the caller did not measure it. */
    val frameTimestampMs: Long = -1L,
    /** Wall time when dispatchGesture's callback completed. -1 if not measured. */
    val dispatchCompletedAtMs: Long = -1L,
    /** Monotonic capture time. Preferred over wall time when both ends are set. */
    val frameElapsedMs: Long = -1L,
    /** Monotonic time when the gesture callback completed. */
    val dispatchCompletedElapsedMs: Long = -1L,
    /** True when the post-move board hash matches the pre-move hash. */
    val boardUnchanged: Boolean = false,
    val preDispatchSequence: Long = -1L,
    val afterSequence: Long = -1L,
    /**
     * True only when the gesture callback completed.
     * Default false: a missing flag is not eligibility.
     */
    val gestureEligible: Boolean = false,
    /**
     * Null when the intended swap cells were not compared.
     * True when those cells differ. That is still not VERIFY SUCCESS.
     */
    val intendedRegionChanged: Boolean? = null,
) {
    /**
     * Strictly later than dispatch completion.
     * Missing, zero, equal, or backwards samples return a reason. Null means
     * the ordering check passed.
     */
    fun timingFailure(): String? {
        val monoSupplied = frameElapsedMs != -1L || dispatchCompletedElapsedMs != -1L
        if (monoSupplied) {
            if (frameElapsedMs <= 0L || dispatchCompletedElapsedMs <= 0L) {
                return "invalid or zero monotonic timestamp"
            }
            if (frameElapsedMs < dispatchCompletedElapsedMs) {
                return "backwards monotonic timestamp"
            }
            if (frameElapsedMs == dispatchCompletedElapsedMs) {
                return "frame timestamp equal to dispatch completion"
            }
            return null
        }
        val wallSupplied = frameTimestampMs > 0L || dispatchCompletedAtMs >= 0L
        if (!wallSupplied) return "missing timestamp"
        if (frameTimestampMs <= 0L || dispatchCompletedAtMs < 0L) {
            return "missing or zero timestamp"
        }
        if (frameTimestampMs < dispatchCompletedAtMs) {
            return "frame timestamp earlier than dispatch completion"
        }
        if (frameTimestampMs == dispatchCompletedAtMs) {
            return "frame timestamp equal to dispatch completion"
        }
        return null
    }

    fun frameIsAfterDispatch(): Boolean = timingFailure() == null

    companion object {
        /**
         * Derive acceptance and freshness from the pre-dispatch sequence and
         * the monotonic clock. Does not look at the board.
         */
        fun derive(
            preDispatchSequence: Long,
            afterSequence: Long,
            afterElapsedMs: Long,
            dispatchCompletedElapsedMs: Long,
            nowElapsedMs: Long,
            gestureEligible: Boolean,
            maxAgeMs: Long = GestureFailSafe.MAX_FRAME_AGE_MS,
        ): VerifyObservation {
            val sequenceNewer = preDispatchSequence >= 0L && afterSequence > preDispatchSequence
            val age = if (afterElapsedMs > 0L && nowElapsedMs > 0L) {
                FrameClock.ageMs(afterElapsedMs, nowElapsedMs)
            } else {
                maxAgeMs + 1L
            }
            val fresh = afterElapsedMs > 0L &&
                nowElapsedMs > 0L &&
                nowElapsedMs >= afterElapsedMs &&
                age <= maxAgeMs
            val reasons = mutableListOf<String>()
            if (!sequenceNewer) {
                reasons += "frame sequence not newer than pre-dispatch " +
                    "($preDispatchSequence -> $afterSequence)"
            }
            if (!fresh) reasons += "frame not fresh age=${age}ms"
            if (!gestureEligible) reasons += "gesture not eligible for verification"
            return VerifyObservation(
                newFrameAccepted = sequenceNewer,
                frameFresh = fresh,
                reason = reasons.joinToString("; "),
                frameElapsedMs = afterElapsedMs,
                dispatchCompletedElapsedMs = dispatchCompletedElapsedMs,
                preDispatchSequence = preDispatchSequence,
                afterSequence = afterSequence,
                gestureEligible = gestureEligible,
            )
        }
    }
}

object VerificationPolicy {
    const val PENDING = "PENDING"
    const val SUCCESS = "SUCCESS"
    const val FAILED = "FAILED"

    /**
     * Whole-board hash changed, or the swapped cells changed, and timing checks
     * passed. This is not proof the intended swap happened. A cascade, refill,
     * or UI change can produce the same observation.
     */
    const val BOARD_CHANGED_UNCONFIRMED = "BOARD CHANGED — MOVE UNCONFIRMED"

    /**
     * A dispatcher return value (including `dispatchGesture == true` /
     * onCompleted) is not evidence the board reacted.
     */
    fun afterDispatch(dispatchSucceeded: Boolean): String =
        if (dispatchSucceeded) PENDING else FAILED

    /**
     * A whole-board change is not VERIFY SUCCESS.
     * Every missing input fails closed. [SUCCESS] is not returned: this policy
     * does not have evidence that the intended swap occurred.
     */
    fun decide(
        newFrameAccepted: Boolean,
        frameFresh: Boolean,
        frameVerifiable: Boolean,
        boardChanged: Boolean,
    ): String {
        if (!newFrameAccepted || !frameFresh) return FAILED
        if (!frameVerifiable) return FAILED
        if (!boardChanged) return FAILED
        return BOARD_CHANGED_UNCONFIRMED
    }

    /**
     * [intendedRegionChanged] false → the board moved somewhere else.
     * true or unknown → [BOARD_CHANGED_UNCONFIRMED], never [SUCCESS].
     */
    fun afterBoardChange(intendedRegionChanged: Boolean?): String = when (intendedRegionChanged) {
        false -> FAILED
        true -> BOARD_CHANGED_UNCONFIRMED
        null -> BOARD_CHANGED_UNCONFIRMED
    }
}

object CoordinateBounds {
    data class Result(val allow: Boolean, val reason: String)

    fun check(gesture: GestureSpec, screenWidth: Int, screenHeight: Int): Result {
        if (!gesture.startX.isFinite() || !gesture.startY.isFinite() ||
            !gesture.endX.isFinite() || !gesture.endY.isFinite()
        ) {
            return Result(false, "non-finite coordinate")
        }
        if (screenWidth <= 0 || screenHeight <= 0) {
            return Result(
                false,
                "screen bounds unknown (${screenWidth}x$screenHeight)",
            )
        }
        val outside = gesture.startX < 0f || gesture.startY < 0f ||
            gesture.endX < 0f || gesture.endY < 0f ||
            gesture.startX >= screenWidth || gesture.endX >= screenWidth ||
            gesture.startY >= screenHeight || gesture.endY >= screenHeight
        if (outside) {
            return Result(
                false,
                "coordinate off-screen start=(${gesture.startX},${gesture.startY}) " +
                    "end=(${gesture.endX},${gesture.endY}) screen=${screenWidth}x$screenHeight",
            )
        }
        return Result(true, "OK")
    }
}

object RuntimeLabels {
    fun autoplay(mode: String): String = when (mode.uppercase()) {
        "RUNNING" -> "RUNNING"
        "PAUSED" -> "PAUSED"
        "STOPPED" -> "STOPPED"
        "ANALYSIS_ONLY" -> "ELEMZÉS – NINCS ÉRINTÉS"
        else -> "IDLE"
    }

    fun capture(raw: String): String = when {
        raw.contains("OFF", ignoreCase = true) ||
            raw.equals("MISSING", ignoreCase = true) ||
            raw.isBlank() || raw == "—" -> "OFF"
        else -> "ON"
    }

    fun dispatch(raw: String): String = when (raw.uppercase()) {
        "SUCCESS" -> "SUCCESS"
        "FAILED" -> "FAILED"
        else -> "NOT STARTED"
    }

    fun verify(raw: String): String = when {
        raw.equals("SUCCESS", ignoreCase = true) -> "SUCCESS"
        raw.equals("FAILED", ignoreCase = true) -> "FAILED"
        raw.contains("UNCONFIRMED", ignoreCase = true) ||
            raw.contains("BOARD CHANGED", ignoreCase = true) ->
            VerificationPolicy.BOARD_CHANGED_UNCONFIRMED
        else -> "PENDING"
    }

    fun gesture(raw: String): String =
        if (raw.equals("CREATED", ignoreCase = true)) "CREATED" else "NOT CREATED"

    fun accessibility(raw: String): String =
        if (raw.contains("DISCONNECTED", ignoreCase = true)) "DISCONNECTED"
        else if (raw.contains("CONNECTED", ignoreCase = true)) "CONNECTED"
        else "DISCONNECTED"

    fun inputReadiness(raw: String): String = when {
        raw.equals("YES", ignoreCase = true) || raw.equals("READY", ignoreCase = true) -> "READY"
        else -> "BLOCKED"
    }

    fun freshness(hasFrame: Boolean, ageMs: Long, maxAgeMs: Long = GestureFailSafe.MAX_FRAME_AGE_MS): String =
        when {
            !hasFrame || ageMs < 0L -> "NONE"
            ageMs > maxAgeMs -> "STALE"
            else -> "FRESH"
        }
}

/**
 * Bubble text for one observed moment. [simulated] is printed only when the
 * caller says the chain was the JVM harness — never implied for device status.
 */
data class RuntimeSnapshot(
    val autoplay: String,
    val capture: String,
    val frameTimestampMs: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val freshness: String,
    val frameAgeMs: Long,
    val visionGate: String,
    val visionReason: String,
    val moveCandidates: Int,
    val selectedMove: String,
    val accessibility: String,
    val inputReadiness: String,
    val inputBlockReason: String?,
    val gesture: String,
    val dispatch: String,
    val verification: String,
    val firstBlock: String?,
    val simulated: Boolean,
) {
    fun lines(): String = buildString {
        if (simulated) {
            appendLine("SIMULATION — not live phone (Tier B)")
        }
        appendLine("BLOKK: ${firstBlock?.takeIf { it.isNotBlank() } ?: "—"}")
        appendLine("AUTO: $autoplay")
        appendLine("CAPTURE: $capture")
        val t = if (frameTimestampMs >= 0L) frameTimestampMs.toString() else "—"
        appendLine("FRAME: t=$t ${frameWidth}x$frameHeight age=${frameAgeMs}ms $freshness")
        appendLine(visionLine())
        appendLine("MOVE: jelölt=$moveCandidates kiválasztott=$selectedMove")
        appendLine("A11Y: $accessibility")
        if (inputReadiness == "READY") {
            appendLine("INPUT: READY")
        } else {
            val why = inputBlockReason?.takeIf { it.isNotBlank() }
            appendLine(if (why == null) "INPUT: BLOCKED" else "INPUT: BLOCKED — $why")
        }
        appendLine("GESTURE: $gesture")
        appendLine("DISPATCH: $dispatch")
        append("VERIFY: $verification")
    }.trimEnd()

    private fun visionLine(): String {
        val hold = visionGate.contains("HOLD", ignoreCase = true)
        val pass = visionGate.contains("PASS", ignoreCase = true) && !hold
        val reason = visionReason.trim()
        return when {
            hold -> {
                val detail = when {
                    reason.isNotBlank() && !visionGate.contains(reason) -> reason
                    visionGate.contains("—") -> visionGate.substringAfter("—").trim()
                    visionGate.length > 4 -> visionGate
                    else -> ""
                }
                if (detail.isBlank() || detail.equals("HOLD", ignoreCase = true)) "VISION: HOLD"
                else "VISION: HOLD — $detail"
            }
            pass -> if (reason.isBlank() || reason.equals("PASS", ignoreCase = true)) "VISION: PASS"
            else "VISION: PASS — $reason"
            else -> "VISION: $visionGate"
        }
    }
}

object SimulationMarker {
    const val BANNER =
        "SIMULATION — not live phone (Tier B). LIVE PHONE: NOT TESTED. " +
            "FIRST REAL AUTOMATIC TOUCH: NOT PROVEN."

    fun label(moves: Int, dispatched: Int): String =
        "SIMULATION moves=$moves dispatched=$dispatched (not a live-phone proof)"
}
