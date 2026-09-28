package com.match3vision.analyzer.capture

/**
 * Post-gesture frame freshness gate.
 *
 * After a gesture is dispatched, only a **NEW** frame (higher sequence id and
 * different capture identity) may be used for verify / next analyze.
 * OLD (lower id) and SAME (identical identity) are forbidden — fail-closed HOLD.
 *
 * Pure Kotlin; no Android types — JVM unit-testable.
 */
class FrameSequenceGate {

    enum class Verdict {
        /** First frame or no pending post-gesture requirement. */
        ALLOW_INITIAL,
        /** Fresh frame after gesture — allowed. */
        ALLOW_NEW,
        /** Same identity as gesture-time frame — forbidden. */
        REJECT_SAME,
        /** Older / rewind sequence — forbidden. */
        REJECT_OLD,
        /** No frame offered while NEW was required. */
        REJECT_MISSING,
    }

    data class Decision(
        val verdict: Verdict,
        val reason: String,
        val allow: Boolean,
    )

    data class FrameId(
        val sequence: Long,
        val identity: Long,
        val timestampMs: Long,
    )

    @Volatile
    private var lastAccepted: FrameId? = null

    @Volatile
    private var nextSequence: Long = 1L

    /** After dispatchGesture — next accepted frame must be NEW. */
    @Volatile
    var requireNewAfterGesture: Boolean = false
        private set

    @Volatile
    private var gestureFrame: FrameId? = null

    fun reset() {
        lastAccepted = null
        nextSequence = 1L
        requireNewAfterGesture = false
        gestureFrame = null
    }

    /** Allocate a monotonic sequence for a newly captured frame. */
    fun allocateSequence(): Long = nextSequence++

    /**
     * Mark that a gesture was dispatched on [frame]. Subsequent [evaluate]
     * must see a different identity with higher sequence before ALLOW_NEW.
     */
    fun markGestureDispatched(frame: FrameId) {
        gestureFrame = frame
        requireNewAfterGesture = true
    }

    fun markGestureDispatched(sequence: Long, identity: Long, timestampMs: Long) {
        markGestureDispatched(FrameId(sequence, identity, timestampMs))
    }

    /**
     * Evaluate whether [frame] may be used for analysis / verify.
     * When [requireNewAfterGesture] is false, any non-null frame is allowed
     * (updates lastAccepted). When true, only a strictly newer identity passes.
     */
    fun evaluate(frame: FrameId?): Decision {
        if (frame == null) {
            return if (requireNewAfterGesture) {
                Decision(
                    Verdict.REJECT_MISSING,
                    HOLD_NO_FRESH_FRAME,
                    allow = false,
                )
            } else {
                Decision(Verdict.ALLOW_INITIAL, "no frame required yet", allow = true)
            }
        }
        val gesture = gestureFrame
        if (requireNewAfterGesture && gesture != null) {
            when {
                frame.identity == gesture.identity ->
                    return Decision(Verdict.REJECT_SAME, HOLD_SAME_FRAME, allow = false)
                frame.sequence < gesture.sequence ||
                    (frame.timestampMs > 0 && gesture.timestampMs > 0 &&
                        frame.timestampMs < gesture.timestampMs) ->
                    return Decision(Verdict.REJECT_OLD, HOLD_OLD_FRAME, allow = false)
                frame.identity != gesture.identity && frame.sequence > gesture.sequence -> {
                    requireNewAfterGesture = false
                    lastAccepted = frame
                    return Decision(Verdict.ALLOW_NEW, "NEW frame after gesture", allow = true)
                }
                else ->
                    // Same sequence number but different identity is still NEW enough.
                    if (frame.identity != gesture.identity) {
                        requireNewAfterGesture = false
                        lastAccepted = frame
                        return Decision(Verdict.ALLOW_NEW, "NEW frame after gesture", allow = true)
                    }
                    return Decision(Verdict.REJECT_SAME, HOLD_SAME_FRAME, allow = false)
            }
        }
        lastAccepted = frame
        return Decision(Verdict.ALLOW_INITIAL, "frame accepted", allow = true)
    }

    fun lastAcceptedOrNull(): FrameId? = lastAccepted

    fun statusText(): String = buildString {
        append("seqGate requireNew=").append(requireNewAfterGesture)
        append(" last=").append(lastAccepted?.let { "seq=${it.sequence}/id=${it.identity}" } ?: "—")
        append(" gest=").append(gestureFrame?.let { "seq=${it.sequence}/id=${it.identity}" } ?: "—")
    }

    companion object {
        const val HOLD_SAME_FRAME =
            "HOLD — SAME frame after gesture (fail-safe; need NEW capture)"
        const val HOLD_OLD_FRAME =
            "HOLD — OLD frame after gesture (fail-safe; need NEW capture)"
        const val HOLD_NO_FRESH_FRAME =
            "HOLD — no fresh frame after gesture (fail-safe)"
        const val HOLD_STALE_FRAME =
            "HOLD — stale frame (age above limit)"
        const val HOLD_CAPTURE_ERROR =
            "HOLD — capture error (no usable frame)"
    }
}
