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
     * Gesture coordinate space. Live loop passes the capture frame size
     * (cell centers are in that bitmap), not a hardcoded device.
     */
    val screenWidth: Int,
    val screenHeight: Int,
    val frameTimestampMs: Long = -1L,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    /** True only for the JVM continuous harness. Never set on the device loop. */
    val simulated: Boolean = false,
)

/** What the verifier is allowed to look at. Missing / stale / non-new → not SUCCESS. */
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
) {
    /**
     * A verify frame must be strictly after dispatch completion.
     * When the caller did not supply times (legacy unit tests), this is not
     * treated as proof — [InputLoopController.completeFeedback] still requires
     * [newFrameAccepted] and [frameFresh]. When times are supplied, a frame
     * that is not later fails closed.
     */
    fun frameIsAfterDispatch(): Boolean {
        if (dispatchCompletedElapsedMs > 0L || frameElapsedMs > 0L) {
            return frameElapsedMs > dispatchCompletedElapsedMs && dispatchCompletedElapsedMs > 0L
        }
        if (dispatchCompletedAtMs >= 0L || frameTimestampMs > 0L) {
            return frameTimestampMs > dispatchCompletedAtMs && dispatchCompletedAtMs >= 0L
        }
        return true
    }
}

object VerificationPolicy {
    const val PENDING = "PENDING"
    const val SUCCESS = "SUCCESS"
    const val FAILED = "FAILED"

    /**
     * A dispatcher return value (including `dispatchGesture == true` /
     * onCompleted) is not evidence the board reacted.
     */
    fun afterDispatch(dispatchSucceeded: Boolean): String =
        if (dispatchSucceeded) PENDING else FAILED

    /**
     * SUCCESS only when a new, fresh, verifiable frame shows a board change.
     * Any missing input fails closed. Does not inspect pixels itself.
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
        return SUCCESS
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

    fun verify(raw: String): String = when (raw.uppercase()) {
        "SUCCESS" -> "SUCCESS"
        "FAILED" -> "FAILED"
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
